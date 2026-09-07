package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import br.com.economize.service.provider.fallback.CoinGeckoSource;
import br.com.economize.service.provider.fallback.FailureSummary;
import br.com.economize.service.provider.fallback.FallbackQuoteSource;
import br.com.economize.service.provider.fallback.FrankfurterSource;
import br.com.economize.service.provider.fallback.PtaxSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moedas e cripto da Home. A AwesomeAPI continua sendo a fonte primária (é a
 * única que devolve o universo inteiro, turismo incluído, em uma chamada), mas
 * deixou de ser a única: quando ela falha — 429 de cota, timeout, 5xx — ou o
 * orçamento diário dela já acabou, a lista vem de uma cascata de fontes
 * públicas sem chave, e só depois disso do snapshot.
 *
 * <pre>
 *   fiat   : Frankfurter (BCE) → PTAX (BCB)
 *   cripto : CoinGecko
 *   resto  : último snapshot bom, item a item, marcado stale
 * </pre>
 *
 * O que a alternativa não cobre (variantes de turismo, ARS) é completado com
 * o item correspondente do snapshot, stale, para a Home não encolher; a lista
 * resultante vira o snapshot novo, cada item com sua fonte e seu
 * {@code asOf} verdadeiros — é isso que o app mostra em "atualizado às".
 */
@Slf4j
@Component
public class AwesomeApiProvider implements MarketDataProvider {

    public static final String SOURCE = "AwesomeAPI";
    static final String SNAPSHOT_KEY = "awesome:all";

    private static final Set<String> CRYPTO_CODES = Set.of("BTC", "ETH", "XRP", "LTC");

    private final WebClient webClient;
    private final String awesomeApiUrl;
    private final MarketSnapshotStore snapshotStore;
    private final AwesomeApiBudget budget;
    private final List<FallbackQuoteSource> fiatSources;
    private final List<FallbackQuoteSource> cryptoSources;

    @Autowired
    public AwesomeApiProvider(WebClient webClient, @Value("${awesome.api.url}") String awesomeApiUrl,
            MarketSnapshotStore snapshotStore, AwesomeApiBudget budget,
            FrankfurterSource frankfurter, PtaxSource ptax, CoinGeckoSource coinGecko) {
        this(webClient, awesomeApiUrl, snapshotStore, budget, List.of(frankfurter, ptax), List.of(coinGecko));
    }

    AwesomeApiProvider(WebClient webClient, String awesomeApiUrl, MarketSnapshotStore snapshotStore,
            AwesomeApiBudget budget, List<FallbackQuoteSource> fiatSources,
            List<FallbackQuoteSource> cryptoSources) {
        this.webClient = webClient;
        this.awesomeApiUrl = awesomeApiUrl;
        this.snapshotStore = snapshotStore;
        this.budget = budget;
        this.fiatSources = fiatSources;
        this.cryptoSources = cryptoSources;
    }

    @Override
    public String getProviderName() {
        return "AwesomeAPI (Currencies)";
    }

    @Override
    public Mono<List<Indicator>> fetchDefaultIndicators() {
        return Mono.defer(() -> {
            if (!budget.tryAcquire()) {
                // um 429 a mais só aprofunda o bloqueio: nem abre conexão
                log.warn("AwesomeAPI fora do orçamento diário ({}/dia); indo direto às fontes alternativas",
                        budget.limit());
                return alternatives();
            }
            return fetchFromAwesome()
                    .onErrorResume(e -> {
                        log.warn("AwesomeAPI falhou ({}); tentando fontes alternativas", FailureSummary.of(e));
                        return alternatives();
                    });
        });
    }

    @Override
    public Mono<List<Indicator>> searchIndicator(String query) {
        // AwesomeAPI não tem busca dinâmica simples por texto, retorna vazio por padrão
        return Mono.just(Collections.emptyList());
    }

    private Mono<List<Indicator>> fetchFromAwesome() {
        return webClient.get()
                .uri(awesomeApiUrl + "/all")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleApiError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
                })
                .map(responseMap -> {
                    Instant fetchedAt = Instant.now();
                    return responseMap.entrySet().stream()
                            .map(entry -> enrichIndicatorData(entry.getKey(), entry.getValue(), fetchedAt))
                            .collect(Collectors.toList());
                })
                .doOnNext(indicators -> snapshotStore.save(SNAPSHOT_KEY, indicators, SOURCE));
    }

    /**
     * Fiat e cripto em paralelo, cada grupo na sua cascata; o que sobrar vem do
     * snapshot. Nenhuma fonte responder é o único caso em que a lista sai
     * inteira do snapshot — ou vazia, se nem ele existir.
     */
    private Mono<List<Indicator>> alternatives() {
        return Mono.zip(firstAvailable(fiatSources), firstAvailable(cryptoSources))
                .flatMap(live -> snapshotStore.lookup(SNAPSHOT_KEY)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .map(previous -> merge(live.getT1(), live.getT2(), previous)));
    }

    /** A primeira fonte da cascata que responde com algo; falha vira um WARN e passa adiante. */
    private Mono<List<Indicator>> firstAvailable(List<FallbackQuoteSource> sources) {
        return Flux.fromIterable(sources)
                .concatMap(source -> source.fetch()
                        .filter(indicators -> !indicators.isEmpty())
                        .onErrorResume(e -> {
                            log.warn("{} indisponível: {}", source.sourceName(), FailureSummary.of(e));
                            return Mono.empty();
                        }))
                .next()
                .defaultIfEmpty(Collections.emptyList());
    }

    private List<Indicator> merge(List<Indicator> fiat, List<Indicator> crypto,
            Optional<List<Indicator>> previous) {
        if (fiat.isEmpty() && crypto.isEmpty()) {
            if (previous.isPresent()) {
                log.warn("Nenhuma fonte de moedas respondeu; servindo snapshot stale com {} indicadores",
                        previous.get().size());
                return previous.get();
            }
            log.error("Nenhuma fonte de moedas respondeu e sem snapshot stale");
            return Collections.emptyList();
        }

        Map<String, Indicator> byId = new LinkedHashMap<>();
        fiat.forEach(indicator -> byId.putIfAbsent(indicator.getId(), indicator));
        crypto.forEach(indicator -> byId.putIfAbsent(indicator.getId(), indicator));
        int live = byId.size();
        // o que a alternativa não cobre (turismo, ARS...) continua vindo do
        // snapshot, já marcado stale e com o asOf original
        previous.ifPresent(old -> old.forEach(indicator -> {
            if (indicator.getId() != null) {
                byId.putIfAbsent(indicator.getId(), indicator);
            }
        }));

        List<Indicator> merged = List.copyOf(byId.values());
        String sources = liveSources(fiat, crypto);
        snapshotStore.save(SNAPSHOT_KEY, merged, sources);
        log.info("Moedas servidas por fonte alternativa ({}): {} vivas, {} do snapshot", sources, live,
                merged.size() - live);
        return merged;
    }

    private static String liveSources(List<Indicator> fiat, List<Indicator> crypto) {
        Set<String> names = new LinkedHashSet<>();
        fiat.stream().map(Indicator::getSource).filter(source -> source != null).forEach(names::add);
        crypto.stream().map(Indicator::getSource).filter(source -> source != null).forEach(names::add);
        return String.join("+", names);
    }

    private Indicator enrichIndicatorData(String key, Indicator indicator, Instant fetchedAt) {
        boolean isCrypto = CRYPTO_CODES.contains(key.toUpperCase());

        if (isCrypto) {
            indicator.setId("crypto_" + key);
            indicator.setType("crypto");
            switch (key.toUpperCase()) {
                case "BTC" -> indicator.setName("Bitcoin");
                case "ETH" -> indicator.setName("Ethereum");
                case "XRP" -> indicator.setName("XRP");
                case "LTC" -> indicator.setName("Litecoin");
            }
        } else {
            indicator.setId("currency_" + key);
            indicator.setType("currency");
            if (key.endsWith("T")) {
                indicator.setName(indicator.getName() + " (Turismo)");
            }
        }
        indicator.setSource(SOURCE);
        indicator.setAsOf(quoteInstant(indicator.getProviderTimestamp(), fetchedAt));
        return indicator;
    }

    /** A AwesomeAPI data cada cotação em segundos de época; sem ela, vale o instante da leitura. */
    private static Instant quoteInstant(String epochSeconds, Instant fallback) {
        if (epochSeconds == null || epochSeconds.isBlank()) {
            return fallback;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Mono<? extends Throwable> handleApiError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(errorBody -> Mono.error(new WebClientResponseException(
                        response.statusCode().value(), "Erro AwesomeAPI: " + errorBody,
                        response.headers().asHttpHeaders(), errorBody.getBytes(), null)));
    }
}
