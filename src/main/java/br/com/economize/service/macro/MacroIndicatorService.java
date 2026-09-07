package br.com.economize.service.macro;

import br.com.economize.dto.indicator.MacroIndicator;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.service.provider.fallback.FailureSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Os indicadores que interferem no que a pessoa normalmente tem — CDB/CDI,
 * Tesouro Direto, poupança, ETF no exterior — todos do SGS do Banco Central.
 *
 * <p>
 * Cada série é pedida em paralelo e falha sozinha: um SGS que não responde o
 * IGP-M não pode sumir com o CDI. O que falhou é completado com o item do
 * snapshot persistido (marcado stale), e a lista final vira o snapshot novo.
 * Cache de 6h porque nada aqui muda mais de uma vez por dia — CDI e PTAX são
 * diários, o resto é mensal.
 *
 * <p>
 * Por que estas séries: 4389 é o CDI anualizado (base 252) que os CDBs
 * seguem; 432 é a META Selic do Copom (a série vem preenchida até a próxima
 * reunião, por isso o valor escolhido é o do último dia até hoje, não o
 * último da lista); 433 e 13522 são o IPCA do mês e o acumulado em 12 meses;
 * 1 é o dólar PTAX de venda; 195 é o rendimento MENSAL da poupança para
 * depósitos feitos na data (a 196 é a mesma informação só no 1º dia do mês,
 * e a 25 é a regra antiga); 189 é o IGP-M do mês.
 */
@Slf4j
@Service
public class MacroIndicatorService {

    static final String SNAPSHOT_KEY = MarketSnapshotStore.DATA_PREFIX + "macro";

    private static final TypeReference<List<MacroIndicator>> TYPE = new TypeReference<>() {
    };
    private static final ZoneId BCB_ZONE = ZoneId.of("America/Sao_Paulo");
    /** Pontos pedidos por série: cobre a meta Selic preenchida para a frente e séries mensais. */
    private static final int LOOKBACK = 20;

    record Series(String code, int sgsId, String name, String unit) {
    }

    static final List<Series> SERIES = List.of(
            new Series("CDI", 4389, "CDI (taxa DI anualizada)", "% a.a."),
            new Series("SELIC", 432, "Selic (meta Copom)", "% a.a."),
            new Series("IPCA_MES", 433, "IPCA do mês", "% a.m."),
            new Series("IPCA_12M", 13522, "IPCA acumulado em 12 meses", "%"),
            new Series("USD_PTAX", 1, "Dólar PTAX (venda)", "BRL"),
            new Series("POUPANCA", 195, "Poupança (rendimento mensal)", "% a.m."),
            new Series("IGPM", 189, "IGP-M do mês", "% a.m."));

    private final BcbSgsClient sgs;
    private final MarketSnapshotStore snapshotStore;
    private final Clock clock;

    @Autowired
    public MacroIndicatorService(BcbSgsClient sgs, MarketSnapshotStore snapshotStore) {
        this(sgs, snapshotStore, Clock.systemUTC());
    }

    MacroIndicatorService(BcbSgsClient sgs, MarketSnapshotStore snapshotStore, Clock clock) {
        this.sgs = sgs;
        this.snapshotStore = snapshotStore;
        this.clock = clock;
    }

    @Cacheable("macro")
    public Mono<List<MacroIndicator>> getMacroIndicators() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(BCB_ZONE));
        return Flux.fromIterable(SERIES)
                .flatMap(series -> sgs.lastValues(series.sgsId(), LOOKBACK)
                        .mapNotNull(points -> toIndicator(series, points, today, now))
                        .onErrorResume(e -> {
                            log.warn("Série SGS {} ({}) indisponível: {}", series.sgsId(), series.code(),
                                    FailureSummary.of(e));
                            return Mono.empty();
                        }))
                .collectList()
                .flatMap(this::completeFromSnapshot);
    }

    /**
     * Ordem fixa das séries, com o que veio vivo e, para o que faltou, o item
     * do snapshot (stale). Item de snapshot com mais de 7 dias não entra: seria
     * eternizar um número velho a cada gravação.
     */
    private Mono<List<MacroIndicator>> completeFromSnapshot(List<MacroIndicator> live) {
        Map<String, MacroIndicator> liveByCode = new HashMap<>();
        live.forEach(item -> liveByCode.put(item.code(), item));

        return snapshotStore.lookupPayload(SNAPSHOT_KEY, TYPE)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .map(previous -> {
                    Map<String, MacroIndicator> previousByCode = new HashMap<>();
                    previous.ifPresent(snapshot -> snapshot.payload()
                            .forEach(item -> previousByCode.put(item.code(), item)));

                    List<MacroIndicator> result = new ArrayList<>();
                    for (Series series : SERIES) {
                        MacroIndicator item = liveByCode.get(series.code());
                        if (item == null) {
                            MacroIndicator old = previousByCode.get(series.code());
                            if (old != null && !isTooOld(old.asOf())) {
                                item = old.asStale();
                            }
                        }
                        if (item != null) {
                            result.add(item);
                        }
                    }

                    if (!live.isEmpty()) {
                        snapshotStore.savePayload(SNAPSHOT_KEY, result, BcbSgsClient.SOURCE);
                    } else if (!result.isEmpty()) {
                        log.warn("SGS indisponível; servindo {} indicadores macro do snapshot", result.size());
                    } else {
                        log.error("SGS indisponível e sem snapshot de indicadores macro");
                    }
                    return result;
                });
    }

    /** O último ponto até hoje (a meta Selic vem preenchida para a frente). */
    static MacroIndicator toIndicator(Series series, List<BcbSgsClient.Point> points, LocalDate today,
            Instant asOf) {
        BcbSgsClient.Point chosen = null;
        for (BcbSgsClient.Point point : points) {
            if (!point.date().isAfter(today) && (chosen == null || point.date().isAfter(chosen.date()))) {
                chosen = point;
            }
        }
        if (chosen == null && !points.isEmpty()) {
            chosen = points.get(points.size() - 1);
        }
        if (chosen == null) {
            return null;
        }
        return new MacroIndicator(series.code(), series.name(), chosen.value(), series.unit(), chosen.date(),
                "Banco Central (SGS " + series.sgsId() + ")", asOf, false);
    }

    private boolean isTooOld(Instant asOf) {
        return asOf == null || Duration.between(asOf, clock.instant()).compareTo(MarketSnapshotStore.MAX_AGE) > 0;
    }
}
