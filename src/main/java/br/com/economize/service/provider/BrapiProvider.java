package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import br.com.economize.dto.indicator.AssetDetail;
import br.com.economize.service.catalog.QuoteBudget;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provedor de ações da B3 (Brapi) e <b>único ponto por onde a cota diária dela é
 * gasta</b>. Todo caminho que pede cotação de ticker — o conjunto padrão do
 * {@code /all}, a busca do usuário e a página do catálogo — passa por
 * {@link #fetchTickers}, e é lá que o {@link QuoteBudget} decide o que ainda
 * cabe no dia. Ter o controle em um lugar só é o que impede um caminho de
 * consumir a cota dos outros e derrubar a Home de todo mundo até a virada do
 * dia.
 */
@Slf4j
@Component
public class BrapiProvider implements MarketDataProvider {

    private final WebClient webClient;
    private final String brapiApiUrl;
    private final String brapiToken;
    private final MarketSnapshotStore snapshotStore;
    private final QuoteBudget quoteBudget;

    // Uma requisição por ticker: a Brapi devolve 400 para o lote (o plano limita
    // a quantidade de ativos por chamada) e o "^BVSP" percent-encodado (%5EBVSP)
    // só é aceito quando vai sozinho no path.
    private static final List<String> DEFAULT_TICKERS = List.of(
            "PETR4", "VALE3", "ITUB4", "MXRF11", "BOVA11", "IVVB11", "^BVSP");

    // Quarentena de ticker que a Brapi disse NÃO EXISTIR. Sem ela, um papel
    // deslistado ou com erro de grafia no catálogo seria repedido em toda página
    // e drenaria a cota sem nunca devolver preço. Só entra aqui quem levou 404:
    // timeout, 429 e 5xx são falhas transitórias — quarentenar por causa delas
    // apagaria da lista, por 6 horas, ativos perfeitamente vivos.
    private static final Duration UNKNOWN_TTL = Duration.ofHours(6);

    private final Cache<String, Boolean> unknownTickers = Caffeine.newBuilder()
            .expireAfterWrite(UNKNOWN_TTL)
            .maximumSize(600)
            .build();

    public BrapiProvider(WebClient webClient,
            @Value("${brapi.api.url}") String brapiApiUrl,
            @Value("${brapi.api.token}") String brapiToken,
            MarketSnapshotStore snapshotStore,
            QuoteBudget quoteBudget) {
        this.webClient = webClient;
        this.brapiApiUrl = brapiApiUrl;
        this.brapiToken = brapiToken;
        this.snapshotStore = snapshotStore;
        this.quoteBudget = quoteBudget;
    }

    @Override
    public String getProviderName() {
        return "Brapi (B3 & Indexes)";
    }

    /**
     * Quantas requisições custa UMA recarga do agregado do /all. Público porque
     * é a variável central da aritmética da cota diária, verificada em teste —
     * acrescentar um ticker padrão encarece o dia inteiro e precisa ser um
     * movimento consciente.
     */
    public static int defaultTickerCount() {
        return DEFAULT_TICKERS.size();
    }

    @Override
    public Mono<List<Indicator>> fetchDefaultIndicators() {
        return fetchTickers(DEFAULT_TICKERS, QuoteBudget.Purpose.HOME);
    }

    @Override
    public Mono<List<Indicator>> searchIndicator(String query) {
        // Permite buscar qualquer ticker na B3 dinamicamente
        List<String> tickers = Arrays.stream(query.toUpperCase().split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
        return fetchTickers(tickers, QuoteBudget.Purpose.ON_DEMAND);
    }

    /**
     * Decide, ANTES de abrir conexão, quais tickers podem ir à rede hoje. O que
     * não couber no orçamento não vira erro nem some da resposta: volta com o
     * último snapshot bom, já marcado como stale. Preço mais velho é aceitável;
     * lista vazia na Home, não.
     */
    private Mono<List<Indicator>> fetchTickers(List<String> tickers, QuoteBudget.Purpose purpose) {
        // repetição no pedido não pode custar duas requisições nem duas linhas
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(tickers));

        // ticker que a Brapi já disse não existir não entra sequer na conta do
        // orçamento: ele não gastaria cota, gastaria a VAGA de outro ativo
        List<String> billable = unique.stream()
                .filter(ticker -> unknownTickers.getIfPresent(ticker) == null)
                .toList();

        int granted = quoteBudget.tryAcquire(billable.size(), purpose);
        Set<String> live = new LinkedHashSet<>(billable.subList(0, granted));

        return Flux.fromIterable(unique)
                .flatMapSequential(ticker -> live.contains(ticker)
                        ? fetchSingleTicker(ticker)
                        : Mono.just(staleOrEmpty(ticker, "sem orçamento diário ou em quarentena")))
                .concatMapIterable(list -> list)
                .collectList();
    }

    /**
     * O detalhe enriquecido de UM ativo (EC-103).
     *
     * <p>Custa <b>uma</b> requisição, a mesma de sempre: o {@code range=1y} vem
     * no próprio {@code /quote}, então a série do ano e a faixa de 52 semanas
     * não abrem conexão nova. A faixa, aliás, já vinha na resposta desde sempre
     * e era descartada no parse.
     *
     * <p>Sem orçamento no dia, devolve o último preço bom marcado como stale e
     * SEM janelas: janela precisa de série, e série só vem da rede. Meia
     * resposta honesta é melhor do que uma variação inventada.
     */
    public Mono<AssetDetail> fetchDetail(String ticker) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (symbol.isEmpty()) return Mono.empty();

        if (unknownTickers.getIfPresent(symbol) != null
                || quoteBudget.tryAcquire(1, QuoteBudget.Purpose.ON_DEMAND) < 1) {
            return Mono.just(staleDetail(symbol));
        }

        return webClient.get()
                .uri(brapiApiUrl + "/quote/" + symbol + "?range=1y&interval=1d")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + brapiToken)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .map(response -> parseDetail(symbol, response))
                .onErrorResume(e -> {
                    if (isNotFound(e)) {
                        unknownTickers.put(symbol, Boolean.TRUE);
                        return Mono.empty();
                    }
                    log.warn("Detalhe indisponível para [{}]: {}", symbol, e.getMessage());
                    return Mono.just(staleDetail(symbol));
                });
    }

    /** Detalhe montado só com o que o snapshot guarda: preço velho, sem série. */
    private AssetDetail staleDetail(String symbol) {
        Indicator known = snapshotStore.find(snapshotKey(symbol))
                .flatMap(list -> list.stream().findFirst())
                .orElse(null);
        return new AssetDetail(symbol,
                known != null ? known.getName() : symbol,
                known != null ? known.getBuy() : null,
                known != null ? known.getVariation() : null,
                null, null, null, List.of(), true);
    }

    @SuppressWarnings("unchecked")
    private AssetDetail parseDetail(String symbol, Map<String, Object> response) {
        List<Map<String, Object>> results = response.containsKey("results")
                ? (List<Map<String, Object>>) response.get("results")
                : List.of();
        if (results.isEmpty()) return staleDetail(symbol);

        Map<String, Object> item = results.get(0);
        BigDecimal price = number(item.get("regularMarketPrice"));
        BigDecimal high = number(item.get("fiftyTwoWeekHigh"));
        BigDecimal low = number(item.get("fiftyTwoWeekLow"));

        List<AssetWindowCalculator.Close> series = new ArrayList<>();
        Object rawSeries = item.get("historicalDataPrice");
        if (rawSeries instanceof List<?> points) {
            for (Object point : points) {
                if (!(point instanceof Map<?, ?> map)) continue;
                BigDecimal close = number(map.get("close"));
                // A Brapi data o ponto em segundos de época, em UTC
                Object rawDate = map.get("date");
                if (close == null || !(rawDate instanceof Number epoch)) continue;
                series.add(new AssetWindowCalculator.Close(
                        java.time.Instant.ofEpochSecond(epoch.longValue())
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                        close));
            }
        }

        // `longName` primeiro: no plano em uso a Brapi devolve o próprio ticker
        // em `shortName` ("PETR4"), e um detalhe cujo título repete o código não
        // acrescenta nada ao que já está no cabeçalho da tela
        return new AssetDetail(
                symbol,
                assetName(item, symbol),
                price,
                number(item.get("regularMarketChangePercent")),
                high,
                low,
                AssetWindowCalculator.rangePosition(price, low, high),
                AssetWindowCalculator.windows(price, number(item.get("regularMarketChangePercent")),
                        series, java.time.LocalDate.now(java.time.ZoneOffset.UTC)),
                false);
    }

    private static BigDecimal number(Object value) {
        return value instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    /** Nome da empresa, com o ticker como último recurso — nunca vazio. */
    private static String assetName(Map<String, Object> item, String symbol) {
        for (String field : List.of("longName", "shortName")) {
            if (item.get(field) instanceof String value && !value.isBlank()
                    && !value.equalsIgnoreCase(symbol)) {
                return value;
            }
        }
        return symbol;
    }

    private Mono<List<Indicator>> fetchSingleTicker(String ticker) {
        String snapshotKey = snapshotKey(ticker);
        return webClient.get()
                .uri(brapiApiUrl + "/quote/" + ticker)
                // token vai no header para nunca aparecer em URL de log/exceção
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + brapiToken)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .map(this::parseBrapiResponse)
                .doOnNext(indicators -> snapshotStore.save(snapshotKey, indicators))
                .onErrorResume(e -> {
                    if (isNotFound(e)) {
                        // resposta EXPLÍCITA de que o ativo não existe: é o único
                        // caso em que repetir a pergunta seria desperdício certo
                        log.info("Brapi não conhece o ticker [{}]; em quarentena por {}h", ticker,
                                UNKNOWN_TTL.toHours());
                        unknownTickers.put(ticker, Boolean.TRUE);
                        return Mono.just(Collections.<Indicator>emptyList());
                    }
                    return Mono.just(staleOrEmpty(ticker, e.getMessage()));
                });
    }

    /** Último preço bom do ticker (marcado stale pelo store) ou lista vazia. */
    private List<Indicator> staleOrEmpty(String ticker, String reason) {
        Optional<List<Indicator>> stale = snapshotStore.find(snapshotKey(ticker));
        if (stale.isPresent()) {
            log.warn("Cotação viva indisponível para [{}] ({}); servindo snapshot stale", ticker, reason);
            return stale.get();
        }
        log.error("Cotação indisponível para [{}] ({}) e sem snapshot stale", ticker, reason);
        return Collections.emptyList();
    }

    /**
     * Ticker fora da lista padrão veio de busca do usuário: o snapshot fica fora
     * do agregado do fallback (ver {@link MarketSnapshotStore#SEARCH_PREFIX}).
     */
    private String snapshotKey(String ticker) {
        return DEFAULT_TICKERS.contains(ticker)
                ? "brapi:" + ticker
                : MarketSnapshotStore.SEARCH_PREFIX + "brapi:" + ticker;
    }

    /**
     * 404 da Brapi é "este papel não existe" — a única resposta que autoriza
     * parar de perguntar. Timeout, 429 e 5xx são transitórios: quarentenar por
     * causa deles apagaria da lista, por horas, ativos perfeitamente vivos. O
     * teste é pelo código de status, e não pela subclasse, porque o erro pode
     * chegar embrulhado por retry/circuit breaker.
     */
    private boolean isNotFound(Throwable error) {
        return error instanceof WebClientResponseException response
                && response.getStatusCode().value() == 404;
    }

    /** Rótulo de procedência que o app mostra ao lado de "atualizado às". */
    public static final String SOURCE = "Brapi";

    @SuppressWarnings("unchecked")
    private List<Indicator> parseBrapiResponse(Map<String, Object> response) {
        List<Indicator> stocks = new ArrayList<>();
        if (response.containsKey("results")) {
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            java.time.Instant fetchedAt = java.time.Instant.now();
            for (Map<String, Object> item : results) {
                Indicator ind = new Indicator();
                String symbol = (String) item.get("symbol");

                boolean isIndex = symbol.startsWith("^");
                ind.setId(isIndex ? "index_" + symbol : "stock_" + symbol);
                ind.setType(isIndex ? "index" : "stock");
                ind.setCode(symbol);
                ind.setName((String) item.get("shortName"));

                Object priceObj = item.get("regularMarketPrice");
                if (priceObj instanceof Number) {
                    ind.setBuy(BigDecimal.valueOf(((Number) priceObj).doubleValue()));
                }

                Object changeObj = item.get("regularMarketChangePercent");
                if (changeObj instanceof Number) {
                    ind.setVariation(BigDecimal.valueOf(((Number) changeObj).doubleValue()));
                }

                ind.setSource(SOURCE);
                ind.setAsOf(marketTime(item.get("regularMarketTime"), fetchedAt));

                stocks.add(ind);
            }
        }
        return stocks;
    }

    /**
     * A Brapi data a cotação em {@code regularMarketTime} (ISO-8601, UTC). Sem
     * ele, ou com ele ilegível, vale o instante da leitura — melhor uma data
     * conservadora do que nenhuma.
     */
    private static java.time.Instant marketTime(Object raw, java.time.Instant fallback) {
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return java.time.Instant.parse(text.trim());
            } catch (java.time.format.DateTimeParseException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
