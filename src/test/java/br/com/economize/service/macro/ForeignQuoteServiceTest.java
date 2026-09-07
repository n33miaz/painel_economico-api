package br.com.economize.service.macro;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.indicator.ForeignQuote;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignQuoteServiceTest {

    private static final TypeReference<ForeignQuote> TYPE = new TypeReference<>() {
    };

    /** Trecho real da resposta do Yahoo para VT em 06/09/2026. */
    private static final String YAHOO = """
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"VT","regularMarketTime":1788552000,
              "regularMarketPrice":161.73,"chartPreviousClose":161.01,
              "longName":"Vanguard Total World Stock Index Fund ETF Shares"}}],"error":null}}
            """;
    private static final String STOOQ = "Symbol,Date,Time,Open,High,Low,Close,Volume\n"
            + "VT.US,2026-09-04,22:00:00,161.5,161.97,161.41,161.73,1525098\n";

    @Mock
    private IndicatorService indicatorService;

    private final List<String> urls = new ArrayList<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private HttpStatus yahooStatus = HttpStatus.OK;
    private String stooqBody = STOOQ;

    private MarketSnapshotStore snapshotStore;
    private ForeignQuoteService service;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        MarketSourcesProperties properties = new MarketSourcesProperties();
        properties.setYahooUrl("https://example.test/yahoo/v8/finance/chart");
        properties.setStooqUrl("https://example.test/stooq/q/l/");
        service = new ForeignQuoteService(StubWebClient.respondingWith(urls, this::respond), properties,
                snapshotStore, indicatorService);

        Indicator usd = new Indicator();
        usd.setId("currency_USD");
        usd.setCode("USD");
        usd.setBuy(new BigDecimal("5.1249"));
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(usd)));
    }

    private ClientResponse respond(ClientRequest request) {
        requests.add(request);
        if (request.url().getHost().equals("example.test") && request.url().getPath().contains("/yahoo/")) {
            return yahooStatus == HttpStatus.OK ? StubWebClient.json(YAHOO)
                    : StubWebClient.status(yahooStatus, "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}");
        }
        return StubWebClient.text("text/csv", stooqBody);
    }

    @Test
    @DisplayName("Yahoo vivo: preço, variação sobre o fechamento anterior, data do pregão e preço em reais")
    void shouldQuoteFromYahoo() {
        StepVerifier.create(service.getQuote("VT", "US"))
                .assertNext(quote -> {
                    assertEquals("VT", quote.symbol());
                    assertEquals("US", quote.market());
                    assertEquals(new BigDecimal("161.73"), quote.price());
                    assertEquals("USD", quote.currency());
                    assertEquals(new BigDecimal("0.7200"), quote.change());
                    assertEquals(new BigDecimal("0.4472"), quote.changePercent());
                    assertEquals(LocalDate.of(2026, 9, 4), quote.date());
                    assertEquals(Instant.ofEpochSecond(1788552000L), quote.asOf());
                    assertEquals(ForeignQuoteService.YAHOO_SOURCE, quote.source());
                    // 161.73 × 5.1249 pelo dólar do /all
                    assertEquals(new BigDecimal("828.85"), quote.priceBrl());
                    assertFalse(quote.stale());
                })
                .verifyComplete();

        assertEquals(List.of("https://example.test/yahoo/v8/finance/chart/VT?range=5d&interval=1d"), urls);
        assertTrue(requests.get(0).headers().getFirst(HttpHeaders.USER_AGENT).startsWith("Mozilla/5.0"),
                "sem User-Agent de navegador o Yahoo responde 429");
        assertEquals(ForeignQuoteService.YAHOO_SOURCE,
                snapshotStore.findPayload("data:quote:US:VT", TYPE).orElseThrow().source());
    }

    @Test
    @DisplayName("Yahoo em 429: cai para o CSV da Stooq, sem variação (a fonte não a dá)")
    void yahooDownShouldFallToStooq() {
        yahooStatus = HttpStatus.TOO_MANY_REQUESTS;

        StepVerifier.create(service.getQuote("VT", "US"))
                .assertNext(quote -> {
                    assertEquals(new BigDecimal("161.73"), quote.price());
                    assertNull(quote.change());
                    assertNull(quote.changePercent());
                    assertEquals(LocalDate.of(2026, 9, 4), quote.date());
                    // 22:00 de Nova York = 02:00Z do dia seguinte
                    assertEquals(Instant.parse("2026-09-05T02:00:00Z"), quote.asOf());
                    assertEquals(ForeignQuoteService.STOOQ_SOURCE, quote.source());
                    assertEquals(new BigDecimal("828.85"), quote.priceBrl());
                })
                .verifyComplete();

        assertEquals("https://example.test/stooq/q/l/?s=vt.us&f=sd2t2ohlcv&h&e=csv", urls.get(1));
    }

    @Test
    @DisplayName("Ninguém conhece o papel: snapshot stale se houver, senão vazio (404 na rota)")
    void unknownSymbolShouldServeSnapshotThenEmpty() {
        yahooStatus = HttpStatus.NOT_FOUND;
        stooqBody = "Symbol,Date,Time,Open,High,Low,Close,Volume\nXPTO.US,N/D,N/D,N/D,N/D,N/D,N/D,N/D\n";

        StepVerifier.create(service.getQuote("XPTO", "US")).verifyComplete();

        snapshotStore.savePayload("data:quote:US:XPTO", new ForeignQuote("XPTO", "US", new BigDecimal("10.00"),
                "USD", null, null, null, LocalDate.of(2026, 9, 3), ForeignQuoteService.YAHOO_SOURCE,
                Instant.parse("2026-09-03T20:00:00Z"), false), ForeignQuoteService.YAHOO_SOURCE);

        StepVerifier.create(service.getQuote("XPTO", "US"))
                .assertNext(quote -> {
                    assertTrue(quote.stale());
                    assertEquals(Instant.parse("2026-09-03T20:00:00Z"), quote.asOf());
                    assertEquals(new BigDecimal("51.25"), quote.priceBrl(), "o dólar de agora converte o preço velho");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Sem dólar disponível a cotação sai sem priceBrl — nunca falha por causa dele")
    void missingUsdShouldLeavePriceBrlNull() {
        when(indicatorService.getAllIndicators()).thenReturn(Mono.error(new RuntimeException("circuito aberto")));

        StepVerifier.create(service.getQuote("VT", "US"))
                .assertNext(quote -> {
                    assertEquals(new BigDecimal("161.73"), quote.price());
                    assertNull(quote.priceBrl());
                })
                .verifyComplete();

        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of()));
        StepVerifier.create(service.getQuote("VT", "US"))
                .assertNext(quote -> assertNull(quote.priceBrl()))
                .verifyComplete();
    }
}
