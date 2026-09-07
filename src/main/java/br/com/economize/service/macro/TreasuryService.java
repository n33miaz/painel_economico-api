package br.com.economize.service.macro;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.indicator.TreasuryBond;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.FailureSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Títulos do Tesouro Direto com taxa e preço do dia.
 *
 * <p>
 * Duas fontes, em ordem. A primeira é o JSON oficial do site do Tesouro
 * ({@code treasurybondsinfo.json}), que traz preço intradiário e aplicação
 * mínima — mas que em 06/09/2026 responde <b>410 Gone</b>, com e sem
 * User-Agent de navegador. Fica como primeira tentativa porque, se voltar, é a
 * fonte mais rica, e a URL é property. A segunda é o CSV "Taxas dos Títulos
 * Ofertados" do Tesouro Transparente (CKAN): público, estável, ~14 MB com o
 * histórico inteiro desde 2004, do dia mais recente para o mais antigo.
 *
 * <p>
 * Do CSV só interessam as ~60 primeiras linhas (o dia mais recente, ~5 KB).
 * O servidor ignora {@code Range} (devolve 206 e manda os 14 MB assim mesmo),
 * então a leitura é em streaming: acumula até 64 KB e cancela a conexão — o
 * container de 512 MB nunca vê o arquivo inteiro. As taxas do CSV são as "da
 * manhã", e a aplicação mínima não vem nele (fica nula).
 *
 * <p>
 * Cache de 1h e snapshot persistido: se as duas fontes falharem, a lista do
 * dia anterior sai marcada como stale.
 */
@Slf4j
@Service
public class TreasuryService {

    public static final String OFFICIAL_SOURCE = "Tesouro Direto";
    public static final String CSV_SOURCE = "Tesouro Transparente";

    static final String SNAPSHOT_KEY = MarketSnapshotStore.DATA_PREFIX + "treasury";
    /** Bytes lidos do CSV antes de cancelar: o dia mais recente cabe em ~5 KB. */
    static final int CSV_READ_LIMIT = 64 * 1024;

    private static final TypeReference<List<TreasuryBond>> TYPE = new TypeReference<>() {
    };
    private static final ZoneId BR_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Horário de abertura do Tesouro Direto — momento de referência das taxas "da manhã". */
    private static final LocalTime MORNING_QUOTE = LocalTime.of(9, 30);

    private final WebClient webClient;
    private final MarketSourcesProperties properties;
    private final MarketSnapshotStore snapshotStore;
    private final Clock clock;

    @Autowired
    public TreasuryService(WebClient webClient, MarketSourcesProperties properties,
            MarketSnapshotStore snapshotStore) {
        this(webClient, properties, snapshotStore, Clock.systemUTC());
    }

    TreasuryService(WebClient webClient, MarketSourcesProperties properties, MarketSnapshotStore snapshotStore,
            Clock clock) {
        this.webClient = webClient;
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.clock = clock;
    }

    @Cacheable("treasury")
    public Mono<List<TreasuryBond>> getBonds() {
        return official()
                .onErrorResume(e -> {
                    log.warn("Tesouro Direto (JSON oficial) indisponível: {}", FailureSummary.of(e));
                    return Mono.empty();
                })
                .filter(bonds -> !bonds.isEmpty())
                .switchIfEmpty(Mono.defer(() -> csv()
                        .onErrorResume(e -> {
                            log.warn("Tesouro Transparente (CSV) indisponível: {}", FailureSummary.of(e));
                            return Mono.empty();
                        })
                        .filter(bonds -> !bonds.isEmpty())))
                .doOnNext(bonds -> snapshotStore.savePayload(SNAPSHOT_KEY, bonds, bonds.get(0).source()))
                .switchIfEmpty(Mono.defer(() -> snapshotStore.lookupPayload(SNAPSHOT_KEY, TYPE)
                        .map(snapshot -> {
                            log.warn("Tesouro: fontes indisponíveis; servindo snapshot de {} ({})",
                                    snapshot.savedAt(), snapshot.source());
                            return snapshot.payload().stream().map(TreasuryBond::asStale).toList();
                        })))
                .defaultIfEmpty(List.of());
    }

    Mono<List<TreasuryBond>> official() {
        return webClient.get()
                .uri(properties.getTreasuryUrl())
                .header(HttpHeaders.USER_AGENT, properties.getBrowserUserAgent())
                .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> parseOfficial(root, clock.instant()));
    }

    /** Lê só o começo do CSV (ver javadoc da classe) e devolve o dia mais recente. */
    Mono<List<TreasuryBond>> csv() {
        return webClient.get()
                .uri(properties.getTreasuryCsvUrl())
                .header(HttpHeaders.USER_AGENT, properties.getBrowserUserAgent())
                .header(HttpHeaders.ACCEPT, "text/csv, text/plain, */*")
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .scan(new ByteArrayOutputStream(), (accumulated, buffer) -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        accumulated.write(bytes, 0, bytes.length);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                    return accumulated;
                })
                // o primeiro elemento é a semente vazia; a leitura para no limite
                .takeUntil(accumulated -> accumulated.size() >= CSV_READ_LIMIT)
                .last()
                .map(accumulated -> parseCsv(accumulated.toString(StandardCharsets.ISO_8859_1)));
    }

    /**
     * Shape oficial: {@code response.TrsrBdTradgList[].TrsrBd{nm, mtrtyDt,
     * anulInvstmtRate, anulRedRate, untrInvstmtVal, untrRedVal, minInvstmtAmt,
     * FinIndxs.nm}}; o momento da cotação vem em {@code response.TrsrBondMkt.qtnDtTm}.
     */
    static List<TreasuryBond> parseOfficial(JsonNode root, Instant fetchedAt) {
        JsonNode response = root.path("response");
        JsonNode list = response.path("TrsrBdTradgList");
        if (!list.isArray()) {
            return List.of();
        }
        Instant asOf = parseIsoLocal(response.path("TrsrBondMkt").path("qtnDtTm").asText(null), fetchedAt);
        List<TreasuryBond> bonds = new ArrayList<>();
        for (JsonNode item : list) {
            JsonNode bond = item.path("TrsrBd");
            String name = bond.path("nm").asText(null);
            LocalDate maturity = parseIsoDate(bond.path("mtrtyDt").asText(null));
            if (name == null || maturity == null) {
                continue;
            }
            String indexerName = bond.path("FinIndxs").path("nm").asText("");
            String indexer = indexerOf(indexerName.isBlank() ? name : indexerName + " " + name);
            bonds.add(new TreasuryBond(name, indexer, maturity,
                    decimal(bond.get("anulInvstmtRate")), decimal(bond.get("anulRedRate")),
                    decimal(bond.get("untrInvstmtVal")), decimal(bond.get("untrRedVal")),
                    decimal(bond.get("minInvstmtAmt")), asOf, OFFICIAL_SOURCE, false));
        }
        return bonds;
    }

    /**
     * CSV do Tesouro Transparente: {@code Tipo Titulo;Data Vencimento;Data Base;
     * Taxa Compra Manha;Taxa Venda Manha;PU Compra Manha;PU Venda Manha;PU Base Manha},
     * decimal com vírgula, do dia mais recente para o mais antigo. Só as linhas
     * da primeira data (a mais recente) interessam; uma linha cortada no fim do
     * trecho lido é simplesmente ignorada.
     */
    static List<TreasuryBond> parseCsv(String text) {
        String[] lines = text.split("\r?\n");
        List<TreasuryBond> bonds = new ArrayList<>();
        String referenceDate = null;
        for (int i = 1; i < lines.length; i++) {
            String[] columns = lines[i].split(";");
            if (columns.length < 7) {
                continue;
            }
            String baseDate = columns[2].trim();
            if (referenceDate == null) {
                referenceDate = baseDate;
            } else if (!referenceDate.equals(baseDate)) {
                break;
            }
            try {
                String type = columns[0].trim();
                LocalDate maturity = LocalDate.parse(columns[1].trim(), BR_DATE);
                Instant asOf = LocalDate.parse(baseDate, BR_DATE).atTime(MORNING_QUOTE).atZone(BR_ZONE).toInstant();
                bonds.add(new TreasuryBond(type + " " + maturity.getYear(), indexerOf(type), maturity,
                        brDecimal(columns[3]), brDecimal(columns[4]), brDecimal(columns[5]), brDecimal(columns[6]),
                        null, asOf, CSV_SOURCE, false));
            } catch (RuntimeException e) {
                // linha malformada (ou cortada): pula
            }
        }
        return bonds;
    }

    /** Renda+ e Educa+ são títulos IPCA+; IGP-M e o que mais aparecer vira OTHER. */
    static String indexerOf(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.contains("SELIC")) {
            return TreasuryBond.SELIC;
        }
        if (upper.contains("IPCA") || upper.contains("RENDA+") || upper.contains("EDUCA+")) {
            return TreasuryBond.IPCA;
        }
        if (upper.contains("PREFIXADO") || upper.contains("PREFIX")) {
            return TreasuryBond.PREFIXADO;
        }
        return TreasuryBond.OTHER;
    }

    private static BigDecimal brDecimal(String raw) {
        String cleaned = raw.trim().replace(".", "").replace(",", ".");
        return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    private static LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parseIsoLocal(String raw, Instant fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(raw).atZone(BR_ZONE).toInstant();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
