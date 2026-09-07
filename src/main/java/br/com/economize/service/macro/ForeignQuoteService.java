package br.com.economize.service.macro;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.indicator.ForeignQuote;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.FailureSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cotação de ETF/ação estrangeira sem chave (ex.: VT), para quem tem parte da
 * carteira no exterior.
 *
 * <p>
 * Duas fontes. Yahoo Finance (chart v8) primeiro: responde 429 "Edge: Too
 * Many Requests" para cliente anônimo e 200 com User-Agent de navegador, e
 * traz preço, fechamento anterior e moeda. Stooq (CSV) em seguida: em
 * 06/09/2026 o endpoint {@code q/l/} responde 404 para qualquer símbolo e o
 * {@code q/d/l/} exige JavaScript — fica como segunda opção, porque a URL é
 * property e o parse está pronto se ele voltar. Sem nenhuma das duas, o
 * snapshot persistido sai marcado stale; sem snapshot, vazio (404 na rota).
 *
 * <p>
 * {@code priceBrl} usa o dólar do agregado da Home ({@code /all}, com cache e
 * snapshot próprios): é o mesmo dólar que o usuário vê na tela, e não custa
 * requisição nova. Cache de 30 min, como o resto do que é cotação.
 */
@Slf4j
@Service
public class ForeignQuoteService {

    public static final String YAHOO_SOURCE = "Yahoo Finance";
    public static final String STOOQ_SOURCE = "Stooq";
    public static final String MARKET_US = "US";

    /** Só o que um ticker tem: letras, ponto e hífen (BRK.B, BF-B). */
    public static final Pattern SYMBOL = Pattern.compile("^[A-Z.\\-]{1,12}$");

    private static final TypeReference<ForeignQuote> TYPE = new TypeReference<>() {
    };
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime US_CLOSE = LocalTime.of(16, 0);

    private final WebClient webClient;
    private final MarketSourcesProperties properties;
    private final MarketSnapshotStore snapshotStore;
    private final IndicatorService indicatorService;

    public ForeignQuoteService(WebClient webClient, MarketSourcesProperties properties,
            MarketSnapshotStore snapshotStore, IndicatorService indicatorService) {
        this.webClient = webClient;
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.indicatorService = indicatorService;
    }

    /** Símbolo e mercado já validados e normalizados pelo controller. Vazio = ninguém conhece o papel. */
    @Cacheable("foreignQuote")
    public Mono<ForeignQuote> getQuote(String symbol, String market) {
        String snapshotKey = MarketSnapshotStore.DATA_PREFIX + "quote:" + market + ":" + symbol;
        return yahoo(symbol, market)
                .onErrorResume(e -> {
                    log.warn("Yahoo Finance indisponível para [{}]: {}", symbol, FailureSummary.of(e));
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> stooq(symbol, market)
                        .onErrorResume(e -> {
                            log.warn("Stooq indisponível para [{}]: {}", symbol, FailureSummary.of(e));
                            return Mono.empty();
                        })))
                .doOnNext(quote -> snapshotStore.savePayload(snapshotKey, quote, quote.source()))
                .switchIfEmpty(Mono.defer(() -> snapshotStore.lookupPayload(snapshotKey, TYPE)
                        .map(snapshot -> {
                            log.warn("Cotação [{}]: fontes indisponíveis; servindo snapshot de {} ({})", symbol,
                                    snapshot.savedAt(), snapshot.source());
                            return snapshot.payload().asStale();
                        })))
                .flatMap(this::withPriceBrl);
    }

    Mono<ForeignQuote> yahoo(String symbol, String market) {
        return webClient.get()
                .uri(properties.getYahooUrl() + "/" + symbol + "?range=5d&interval=1d")
                .header(HttpHeaders.USER_AGENT, properties.getBrowserUserAgent())
                .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(root -> Mono.justOrEmpty(parseYahoo(root, symbol, market)));
    }

    Mono<ForeignQuote> stooq(String symbol, String market) {
        String stooqSymbol = symbol.toLowerCase(Locale.ROOT) + "." + market.toLowerCase(Locale.ROOT);
        return webClient.get()
                .uri(properties.getStooqUrl() + "?s=" + stooqSymbol + "&f=sd2t2ohlcv&h&e=csv")
                .header(HttpHeaders.USER_AGENT, properties.getBrowserUserAgent())
                .header(HttpHeaders.ACCEPT, "text/csv, text/plain, */*")
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(csv -> Mono.justOrEmpty(parseStooq(csv, symbol, market)));
    }

    /**
     * Shape: {@code chart.result[0].meta{regularMarketPrice, chartPreviousClose,
     * currency, regularMarketTime}}. Papel desconhecido vem como
     * {@code chart.error} (com 404), e aqui vira vazio.
     */
    static ForeignQuote parseYahoo(JsonNode root, String symbol, String market) {
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return null;
        }
        JsonNode meta = result.get(0).path("meta");
        BigDecimal price = decimal(meta.get("regularMarketPrice"));
        if (price == null) {
            return null;
        }
        BigDecimal previousClose = decimal(meta.get("chartPreviousClose"));
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (previousClose != null && previousClose.signum() > 0) {
            change = price.subtract(previousClose).setScale(4, RoundingMode.HALF_UP);
            changePercent = change.divide(previousClose, MathContext.DECIMAL64)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
        }
        Instant asOf = meta.path("regularMarketTime").isNumber()
                ? Instant.ofEpochSecond(meta.get("regularMarketTime").asLong())
                : Instant.now();
        String currency = meta.path("currency").asText("USD");
        return new ForeignQuote(symbol, market, price, currency, null, change, changePercent,
                asOf.atZone(US_ZONE).toLocalDate(), YAHOO_SOURCE, asOf, false);
    }

    /**
     * CSV: {@code Symbol,Date,Time,Open,High,Low,Close,Volume}; papel desconhecido
     * vem com {@code N/D} nas colunas de preço. Sem fechamento anterior, a
     * variação fica nula.
     */
    static ForeignQuote parseStooq(String csv, String symbol, String market) {
        if (csv == null) {
            return null;
        }
        String[] lines = csv.trim().split("\r?\n");
        if (lines.length < 2) {
            return null;
        }
        String[] columns = lines[1].split(",");
        if (columns.length < 7) {
            return null;
        }
        BigDecimal close = parseDecimal(columns[6]);
        if (close == null) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(columns[1].trim());
        } catch (RuntimeException e) {
            return null;
        }
        Instant asOf;
        try {
            asOf = date.atTime(LocalTime.parse(columns[2].trim())).atZone(US_ZONE).toInstant();
        } catch (RuntimeException e) {
            asOf = date.atTime(US_CLOSE).atZone(US_ZONE).toInstant();
        }
        return new ForeignQuote(symbol, market, close, "USD", null, null, null, date, STOOQ_SOURCE, asOf, false);
    }

    /** Converte pelo dólar da Home; sem dólar (ou cotação que não é em USD), fica sem priceBrl. */
    private Mono<ForeignQuote> withPriceBrl(ForeignQuote quote) {
        if (!"USD".equalsIgnoreCase(quote.currency()) || quote.price() == null) {
            return Mono.just(quote);
        }
        return indicatorService.getAllIndicators()
                .flatMap(indicators -> Mono.justOrEmpty(indicators.stream()
                        .filter(indicator -> "currency_USD".equals(indicator.getId()))
                        .map(Indicator::getBuy)
                        .filter(buy -> buy != null && buy.signum() > 0)
                        .findFirst()))
                .map(usd -> quote.withPriceBrl(quote.price().multiply(usd).setScale(2, RoundingMode.HALF_UP)))
                .defaultIfEmpty(quote)
                .onErrorResume(e -> Mono.just(quote));
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    private static BigDecimal parseDecimal(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
