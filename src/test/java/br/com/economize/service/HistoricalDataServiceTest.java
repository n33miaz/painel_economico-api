package br.com.economize.service;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.service.provider.AwesomeApiBudget;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.service.provider.fallback.CoinGeckoSource;
import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cadeia do histórico com um transporte falso que decide pela URL: a
 * AwesomeAPI pode estar viva, em 429 ou fora do orçamento; SGS e CoinGecko
 * respondem o que o teste mandar.
 */
class HistoricalDataServiceTest {

    private static final TypeReference<List<HistoricalDataPoint>> POINTS = new TypeReference<>() {
    };

    private static final String AWESOME_DAILY = """
            [{"code":"USD","codein":"BRL","high":"5.1366","low":"5.0998","bid":"5.1249","timestamp":"1788557375"},
             {"high":"5.1121","low":"5.0702","bid":"5.0998","timestamp":"1788474606"}]
            """;
    private static final String SGS_USD = """
            [{"data":"03/09/2026","valor":"5.0962"},{"data":"04/09/2026","valor":"5.1253"}]
            """;
    private static final String COINGECKO_BTC = """
            {"prices":[[1788098400000, 409620.33],[1788184800000, 403224.83]]}
            """;

    private final List<String> urls = new ArrayList<>();
    private final Set<String> failing = new java.util.HashSet<>();
    private MarketSnapshotStore snapshotStore;
    private MarketSourcesProperties properties;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        properties = new MarketSourcesProperties();
    }

    private HistoricalDataService service(int awesomeBudget) {
        properties.setAwesomeDailyBudget(awesomeBudget);
        properties.setBcbSgsUrl("https://example.test/sgs");
        properties.setCoingeckoUrl("https://example.test/coingecko");
        WebClient webClient = StubWebClient.respondingWith(urls, this::respond);
        return new HistoricalDataService(webClient, "https://example.test/awesome", new AwesomeApiBudget(properties),
                new BcbSgsClient(webClient, properties), new CoinGeckoSource(webClient, properties), snapshotStore);
    }

    private ClientResponse respond(org.springframework.web.reactive.function.client.ClientRequest request) {
        String url = request.url().toString();
        if (url.contains("/awesome/")) {
            return failing.contains("awesome")
                    ? StubWebClient.status(HttpStatus.TOO_MANY_REQUESTS, "{\"code\":\"QuotaExceeded\"}")
                    : StubWebClient.json(AWESOME_DAILY);
        }
        if (url.contains("/sgs/")) {
            return failing.contains("sgs")
                    ? StubWebClient.status(HttpStatus.SERVICE_UNAVAILABLE, "")
                    : StubWebClient.json(SGS_USD);
        }
        if (url.contains("/coingecko/")) {
            return failing.contains("coingecko")
                    ? StubWebClient.status(HttpStatus.TOO_MANY_REQUESTS, "")
                    : StubWebClient.json(COINGECKO_BTC);
        }
        throw new IllegalStateException("URL inesperada: " + url);
    }

    private long count(String fragment) {
        return urls.stream().filter(url -> url.contains(fragment)).count();
    }

    @Test
    @DisplayName("AwesomeAPI viva: série dela, snapshot gravado com a fonte dela")
    void shouldUseAwesomeApiWhenAvailable() {
        StepVerifier.create(service(10).getHistoricalData("currency_USD", 7))
                .assertNext(points -> {
                    assertEquals(2, points.size());
                    assertEquals("1788557375", points.get(0).getTimestamp());
                    assertEquals(new BigDecimal("5.1366"), points.get(0).getHigh());
                })
                .verifyComplete();

        assertEquals(List.of("https://example.test/awesome/daily/USD-BRL/7"), urls);
        MarketSnapshotStore.Snapshot<List<HistoricalDataPoint>> snapshot = snapshotStore
                .findPayload("data:historical:USD:7", POINTS).orElseThrow();
        assertEquals(HistoricalDataService.AWESOME_SOURCE, snapshot.source());
    }

    @Test
    @DisplayName("429 da AwesomeAPI: moeda cai para a série de venda do SGS, mais recente primeiro")
    void quotaExceededShouldFallToSgsForFiat() {
        failing.add("awesome");

        StepVerifier.create(service(10).getHistoricalData("USD", 7))
                .assertNext(points -> {
                    assertEquals(2, points.size());
                    assertEquals(new BigDecimal("5.1253"), points.get(0).getHigh(), "04/09 antes de 03/09");
                    // 04/09/2026 13:00 de Brasília = 16:00Z
                    assertEquals("1788537600", points.get(0).getTimestamp());
                })
                .verifyComplete();

        assertEquals(1, count("/awesome/daily/USD-BRL/7"));
        assertEquals(1, count("/sgs/bcdata.sgs.1/dados/ultimos/7"));
        assertEquals(BcbSgsClient.SOURCE,
                snapshotStore.findPayload("data:historical:USD:7", POINTS).orElseThrow().source());
    }

    @Test
    @DisplayName("Cripto cai para a CoinGecko, não para o SGS")
    void cryptoShouldFallToCoinGecko() {
        failing.add("awesome");

        StepVerifier.create(service(10).getHistoricalData("crypto_BTC", 7))
                .assertNext(points -> {
                    assertEquals(2, points.size());
                    assertEquals(new BigDecimal("403224.83"), points.get(0).getHigh());
                })
                .verifyComplete();

        assertEquals(1, count("/coingecko/coins/bitcoin/market_chart?vs_currency=brl&days=7"));
        assertEquals(0, count("/sgs/"));
    }

    @Test
    @DisplayName("Orçamento esgotado: a AwesomeAPI nem é chamada")
    void exhaustedBudgetShouldSkipAwesomeApi() {
        StepVerifier.create(service(0).getHistoricalData("USD", 7)).expectNextCount(1).verifyComplete();

        assertEquals(0, count("/awesome/"), "sem orçamento não sai requisição à AwesomeAPI");
        assertEquals(1, count("/sgs/"));
    }

    @Test
    @DisplayName("Tudo fora: serve o snapshot; sem snapshot, lista vazia (nunca erro)")
    void everythingDownShouldServeSnapshotThenEmpty() {
        failing.add("awesome");
        failing.add("sgs");
        HistoricalDataPoint old = new HistoricalDataPoint();
        old.setTimestamp("1788000000");
        old.setHigh(new BigDecimal("5.00"));
        snapshotStore.savePayload("data:historical:USD:7", List.of(old), HistoricalDataService.AWESOME_SOURCE);

        StepVerifier.create(service(10).getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(new BigDecimal("5.00"), points.get(0).getHigh()))
                .verifyComplete();

        StepVerifier.create(service(10).getHistoricalData("EUR", 30))
                .assertNext(points -> assertTrue(points.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Moeda sem série mapeada no SGS vai do 429 direto ao snapshot/vazio")
    void unmappedCurrencyShouldNotHitSgs() {
        failing.add("awesome");

        StepVerifier.create(service(10).getHistoricalData("ARS", 7))
                .assertNext(points -> assertTrue(points.isEmpty()))
                .verifyComplete();

        assertEquals(0, count("/sgs/"));
    }

    @Test
    @DisplayName("Código inválido continua sendo erro de argumento")
    void invalidCodeShouldError() {
        StepVerifier.create(service(10).getHistoricalData("US", 7))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
