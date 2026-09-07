package br.com.economize.service;

import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.service.provider.AwesomeApiBudget;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.service.provider.fallback.CoinGeckoSource;
import br.com.economize.service.provider.fallback.FailureSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Série diária de uma moeda ou cripto para o gráfico do app.
 *
 * <p>
 * A fonte primária continua sendo o {@code /daily} da AwesomeAPI — no mesmo
 * shape de sempre ({@code timestamp} em segundos de época, {@code high}). O que
 * mudou: a chamada passa pelo mesmo orçamento diário da Home, e quando ela
 * falha (ou o orçamento acabou) a série vem do SGS do Banco Central para as
 * moedas e da CoinGecko para as cripto, e só depois do snapshot persistido.
 * Falha total devolve lista vazia, como sempre — o gráfico mostra "sem dados",
 * não erro.
 *
 * <p>
 * Cache de 1h por (moeda, janela): série diária não muda mais rápido que isso,
 * e a AwesomeAPI cobra cada consulta da mesma cota que já derruba a Home.
 */
@Slf4j
@Service
public class HistoricalDataService {

    public static final String AWESOME_SOURCE = "AwesomeAPI";

    private static final TypeReference<List<HistoricalDataPoint>> POINTS = new TypeReference<>() {
    };
    private static final ZoneId BCB_ZONE = ZoneId.of("America/Sao_Paulo");
    /** O boletim PTAX de fechamento sai por volta das 13h de Brasília. */
    private static final int PTAX_CLOSE_HOUR = 13;

    /** Moeda → série SGS de venda (ver {@link BcbSgsClient}). */
    private static final Map<String, Integer> SGS_SERIES = Map.of(
            "USD", BcbSgsClient.USD_SELL,
            "EUR", BcbSgsClient.EUR_SELL,
            "JPY", BcbSgsClient.JPY_SELL,
            "GBP", BcbSgsClient.GBP_SELL,
            "CHF", BcbSgsClient.CHF_SELL,
            "CNY", BcbSgsClient.CNY_SELL,
            "AUD", BcbSgsClient.AUD_SELL,
            "CAD", BcbSgsClient.CAD_SELL);

    private final WebClient webClient;
    private final String awesomeApiUrl;
    private final AwesomeApiBudget budget;
    private final BcbSgsClient sgs;
    private final CoinGeckoSource coinGecko;
    private final MarketSnapshotStore snapshotStore;

    public HistoricalDataService(WebClient webClient, @Value("${awesome.api.url}") String awesomeApiUrl,
            AwesomeApiBudget budget, BcbSgsClient sgs, CoinGeckoSource coinGecko,
            MarketSnapshotStore snapshotStore) {
        this.webClient = webClient;
        this.awesomeApiUrl = awesomeApiUrl;
        this.budget = budget;
        this.sgs = sgs;
        this.coinGecko = coinGecko;
        this.snapshotStore = snapshotStore;
    }

    @Cacheable("historical")
    public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
        if (currencyCode == null || currencyCode.length() < 3) {
            return Mono.error(new IllegalArgumentException("Código de moeda inválido"));
        }
        String code = currencyCode.replace("currency_", "").replace("crypto_", "").toUpperCase(Locale.ROOT);
        boolean crypto = currencyCode.startsWith("crypto_") || CoinGeckoSource.supports(code);
        String snapshotKey = MarketSnapshotStore.DATA_PREFIX + "historical:" + code + ":" + days;

        return fromAwesome(code, days)
                .doOnNext(points -> snapshotStore.savePayload(snapshotKey, points, AWESOME_SOURCE))
                .switchIfEmpty(Mono.defer(() -> fromAlternative(code, days, crypto)
                        .doOnNext(points -> snapshotStore.savePayload(snapshotKey, points,
                                crypto ? CoinGeckoSource.SOURCE : BcbSgsClient.SOURCE))))
                .switchIfEmpty(Mono.defer(() -> snapshotStore.lookupPayload(snapshotKey, POINTS)
                        .map(snapshot -> {
                            log.warn("Histórico {} ({}d): fontes indisponíveis; servindo snapshot de {} ({})",
                                    code, days, snapshot.savedAt(), snapshot.source());
                            return snapshot.payload();
                        })))
                .defaultIfEmpty(Collections.emptyList());
    }

    /** AwesomeAPI, dentro do orçamento diário; vazio quando não deu (já logado). */
    private Mono<List<HistoricalDataPoint>> fromAwesome(String code, int days) {
        return Mono.defer(() -> {
            if (!budget.tryAcquire()) {
                log.warn("Histórico {} ({}d): orçamento diário da AwesomeAPI esgotado; fonte alternativa", code,
                        days);
                return Mono.empty();
            }
            return webClient.get()
                    .uri(awesomeApiUrl + String.format("/daily/%s-BRL/%d", code, days))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError,
                            response -> Mono.error(new RuntimeException("Erro API Histórico " + response.statusCode())))
                    .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                    })
                    .filter(points -> !points.isEmpty())
                    .onErrorResume(e -> {
                        log.warn("Histórico {} ({}d): AwesomeAPI falhou ({}); fonte alternativa", code, days,
                                FailureSummary.of(e));
                        return Mono.empty();
                    });
        });
    }

    /** CoinGecko para cripto, SGS para moedas com série mapeada; vazio quando não há como. */
    private Mono<List<HistoricalDataPoint>> fromAlternative(String code, int days, boolean crypto) {
        Mono<List<HistoricalDataPoint>> alternative;
        if (crypto) {
            alternative = coinGecko.history(code, days);
        } else {
            Integer seriesId = SGS_SERIES.get(code);
            if (seriesId == null) {
                return Mono.empty();
            }
            alternative = sgs.lastValues(seriesId, days).map(HistoricalDataService::fromSgsPoints);
        }
        return alternative
                .filter(points -> !points.isEmpty())
                .onErrorResume(e -> {
                    log.warn("Histórico {} ({}d): fonte alternativa falhou ({})", code, days, FailureSummary.of(e));
                    return Mono.empty();
                });
    }

    /** Série do SGS no shape do /daily: mais recente primeiro, data às 13h de Brasília. */
    static List<HistoricalDataPoint> fromSgsPoints(List<BcbSgsClient.Point> points) {
        List<HistoricalDataPoint> result = new ArrayList<>(points.size());
        for (int i = points.size() - 1; i >= 0; i--) {
            BcbSgsClient.Point point = points.get(i);
            HistoricalDataPoint item = new HistoricalDataPoint();
            item.setTimestamp(String.valueOf(
                    point.date().atTime(PTAX_CLOSE_HOUR, 0).atZone(BCB_ZONE).toEpochSecond()));
            item.setHigh(point.value());
            result.add(item);
        }
        return result;
    }
}
