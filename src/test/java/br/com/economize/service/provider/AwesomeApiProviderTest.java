package br.com.economize.service.provider;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.service.provider.fallback.FallbackQuoteSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({ "unchecked", "rawtypes" })
class AwesomeApiProviderTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    @Mock
    private FallbackQuoteSource frankfurter;

    @Mock
    private FallbackQuoteSource ptax;

    @Mock
    private FallbackQuoteSource coinGecko;

    private MarketSnapshotStore snapshotStore;
    private MarketSourcesProperties properties;
    private AwesomeApiProvider provider;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        properties = new MarketSourcesProperties();
        provider = newProvider(new AwesomeApiBudget(properties));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        when(frankfurter.sourceName()).thenReturn("Frankfurter (BCE)");
        when(ptax.sourceName()).thenReturn("BCB PTAX");
        when(coinGecko.sourceName()).thenReturn("CoinGecko");
        // por padrão nenhuma alternativa responde; cada teste liga a que quer
        when(frankfurter.fetch()).thenReturn(Mono.error(new RuntimeException("frankfurter fora")));
        when(ptax.fetch()).thenReturn(Mono.error(new RuntimeException("ptax fora")));
        when(coinGecko.fetch()).thenReturn(Mono.error(new RuntimeException("coingecko fora")));
    }

    private AwesomeApiProvider newProvider(AwesomeApiBudget budget) {
        return new AwesomeApiProvider(webClient, "https://example.test/awesome", snapshotStore, budget,
                List.of(frankfurter, ptax), List.of(coinGecko));
    }

    private Map<String, Indicator> awesomeResponse() {
        Map<String, Indicator> response = new LinkedHashMap<>();
        Indicator usd = new Indicator();
        usd.setCode("USD");
        usd.setCodeIn("BRL");
        usd.setName("Dólar Americano/Real Brasileiro");
        usd.setBuy(new BigDecimal("5.40"));
        usd.setProviderTimestamp("1788557375");
        response.put("USD", usd);

        Indicator usdt = new Indicator();
        usdt.setCode("USD");
        usdt.setCodeIn("BRLT");
        usdt.setName("Dólar Americano/Real Brasileiro");
        usdt.setBuy(new BigDecimal("5.60"));
        usdt.setProviderTimestamp("1788557375");
        response.put("USDT", usdt);
        return response;
    }

    private Indicator alternative(String id, String type, String code, String source, String price) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setType(type);
        indicator.setCode(code);
        indicator.setCodeIn("BRL");
        indicator.setBuy(new BigDecimal(price));
        indicator.setSell(new BigDecimal(price));
        indicator.setSource(source);
        indicator.setAsOf(Instant.parse("2026-09-04T14:00:00Z"));
        return indicator;
    }

    private WebClientResponseException quotaExceeded() {
        return new WebClientResponseException(429, "Erro AwesomeAPI: {\"status\":429,\"code\":\"QuotaExceeded\"}",
                HttpHeaders.EMPTY, new byte[0], null);
    }

    private void awesomeReturns(Mono<Map<String, Indicator>> first, Mono<Map<String, Indicator>>... next) {
        var stubbing = when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(first);
        for (Mono<Map<String, Indicator>> mono : next) {
            stubbing = stubbing.thenReturn(mono);
        }
    }

    @Test
    @DisplayName("Deve buscar moedas, preencher fonte e data da cotação e guardar snapshot")
    void shouldFetchAndStoreSnapshot() {
        awesomeReturns(Mono.just(awesomeResponse()));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(2, indicators.size());
                    Indicator usd = indicators.get(0);
                    assertEquals("currency_USD", usd.getId());
                    assertEquals(AwesomeApiProvider.SOURCE, usd.getSource());
                    // a data vem da própria cotação (época em segundos), não da leitura
                    assertEquals(Instant.ofEpochSecond(1788557375L), usd.getAsOf());
                    assertFalse(usd.isStale());
                    assertEquals("Dólar Americano/Real Brasileiro (Turismo)", indicators.get(1).getName());
                })
                .verifyComplete();

        assertTrue(snapshotStore.find("awesome:all").isPresent());
        verify(frankfurter, never()).fetch();
    }

    @Test
    @DisplayName("429 da AwesomeAPI cai para Frankfurter + CoinGecko antes do snapshot, com a fonte de cada item")
    void quotaExceededShouldFallToAlternativesBeforeSnapshot() {
        awesomeReturns(Mono.error(quotaExceeded()));
        when(frankfurter.fetch()).thenReturn(Mono.just(List.of(
                alternative("currency_USD", "currency", "USD", "Frankfurter (BCE)", "5.11"))));
        when(coinGecko.fetch()).thenReturn(Mono.just(List.of(
                alternative("crypto_BTC", "crypto", "BTC", "CoinGecko", "409140"))));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(2, indicators.size());
                    assertEquals("Frankfurter (BCE)", indicators.get(0).getSource());
                    assertEquals("CoinGecko", indicators.get(1).getSource());
                    assertTrue(indicators.stream().noneMatch(Indicator::isStale), "fonte alternativa é preço vivo");
                    assertTrue(indicators.stream().allMatch(i -> i.getAsOf() != null));
                })
                .verifyComplete();

        // a lista alternativa vira o snapshot novo, com a fonte verdadeira
        Optional<List<Indicator>> snapshot = snapshotStore.find("awesome:all");
        assertTrue(snapshot.isPresent());
        assertEquals("Frankfurter (BCE)", snapshot.get().get(0).getSource());
        verify(ptax, never()).fetch();
    }

    @Test
    @DisplayName("Frankfurter fora leva ao PTAX; cripto sem fonte simplesmente não entra")
    void shouldCascadeToPtaxWhenFrankfurterFails() {
        awesomeReturns(Mono.error(quotaExceeded()));
        when(ptax.fetch()).thenReturn(Mono.just(List.of(
                alternative("currency_USD", "currency", "USD", "BCB PTAX", "5.1253"))));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("BCB PTAX", indicators.get(0).getSource());
                })
                .verifyComplete();

        verify(frankfurter).fetch();
        verify(ptax).fetch();
    }

    @Test
    @DisplayName("O que a alternativa não cobre vem do snapshot, stale e com o asOf original")
    void alternativeShouldBeCompletedWithSnapshotItems() {
        awesomeReturns(Mono.just(awesomeResponse()), Mono.error(quotaExceeded()));
        StepVerifier.create(provider.fetchDefaultIndicators()).expectNextCount(1).verifyComplete();

        when(frankfurter.fetch()).thenReturn(Mono.just(List.of(
                alternative("currency_USD", "currency", "USD", "Frankfurter (BCE)", "5.11"))));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(2, indicators.size(), "USD vivo + turismo do snapshot");
                    Indicator usd = indicators.get(0);
                    assertEquals("currency_USD", usd.getId());
                    assertFalse(usd.isStale());
                    assertEquals("Frankfurter (BCE)", usd.getSource());

                    Indicator turismo = indicators.get(1);
                    assertEquals("currency_USDT", turismo.getId());
                    assertTrue(turismo.isStale(), "item reaproveitado do snapshot é preço velho");
                    assertEquals(AwesomeApiProvider.SOURCE, turismo.getSource());
                    assertEquals(Instant.ofEpochSecond(1788557375L), turismo.getAsOf());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Tudo fora: serve o snapshot stale inteiro, com fonte e data preservadas")
    void shouldServeStaleSnapshotWhenEverythingFails() {
        awesomeReturns(Mono.just(awesomeResponse()), Mono.error(quotaExceeded()));
        StepVerifier.create(provider.fetchDefaultIndicators()).expectNextCount(1).verifyComplete();

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(2, indicators.size());
                    assertTrue(indicators.stream().allMatch(Indicator::isStale));
                    assertEquals(AwesomeApiProvider.SOURCE, indicators.get(0).getSource());
                    assertNotNull(indicators.get(0).getAsOf());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve retornar lista vazia quando falhar sem alternativa e sem snapshot stale")
    void shouldReturnEmptyOnFailureWithoutStale() {
        awesomeReturns(Mono.error(quotaExceeded()));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Orçamento diário estourado pula a AwesomeAPI sem abrir conexão")
    void exhaustedBudgetShouldSkipAwesomeApi() {
        properties.setAwesomeDailyBudget(0);
        provider = newProvider(new AwesomeApiBudget(properties));
        when(frankfurter.fetch()).thenReturn(Mono.just(List.of(
                alternative("currency_USD", "currency", "USD", "Frankfurter (BCE)", "5.11"))));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("Frankfurter (BCE)", indicators.get(0).getSource());
                })
                .verifyComplete();

        verify(webClient, never()).get();
    }
}
