package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.indicator.ForeignQuote;
import br.com.economize.dto.indicator.MacroIndicator;
import br.com.economize.dto.indicator.TreasuryBond;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.macro.ForeignQuoteService;
import br.com.economize.service.macro.MacroIndicatorService;
import br.com.economize.service.macro.TreasuryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(MarketReferenceController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
class MarketReferenceControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private MacroIndicatorService macroIndicatorService;

    @MockitoBean
    private TreasuryService treasuryService;

    @MockitoBean
    private ForeignQuoteService foreignQuoteService;

    private static final Instant AS_OF = Instant.parse("2026-09-06T14:02:00Z");

    @Test
    @DisplayName("GET /macro - Lista com código, valor, unidade, data de referência, fonte, asOf e stale")
    void shouldReturnMacroIndicators() {
        when(macroIndicatorService.getMacroIndicators()).thenReturn(Mono.just(List.of(
                new MacroIndicator("CDI", "CDI (taxa DI anualizada)", new BigDecimal("13.90"), "% a.a.",
                        LocalDate.of(2026, 9, 3), "Banco Central (SGS 4389)", AS_OF, false))));

        webTestClient.get()
                .uri("/api/v1/indicators/macro")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].code").isEqualTo("CDI")
                .jsonPath("$[0].value").isEqualTo(13.90)
                .jsonPath("$[0].unit").isEqualTo("% a.a.")
                .jsonPath("$[0].referenceDate").isEqualTo("2026-09-03")
                .jsonPath("$[0].source").isEqualTo("Banco Central (SGS 4389)")
                .jsonPath("$[0].asOf").isEqualTo("2026-09-06T14:02:00Z")
                .jsonPath("$[0].stale").isEqualTo(false);
    }

    @Test
    @DisplayName("GET /treasury - Títulos com indexador, vencimento, taxas e preços")
    void shouldReturnTreasuryBonds() {
        when(treasuryService.getBonds()).thenReturn(Mono.just(List.of(
                new TreasuryBond("Tesouro Selic 2029", TreasuryBond.SELIC, LocalDate.of(2029, 3, 1),
                        new BigDecimal("0.03"), new BigDecimal("0.04"), new BigDecimal("19810.47"),
                        new BigDecimal("19795.28"), null, AS_OF, "Tesouro Transparente", false))));

        webTestClient.get()
                .uri("/api/v1/indicators/treasury")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("Tesouro Selic 2029")
                .jsonPath("$[0].indexer").isEqualTo("SELIC")
                .jsonPath("$[0].maturity").isEqualTo("2029-03-01")
                .jsonPath("$[0].annualRateBuy").isEqualTo(0.03)
                .jsonPath("$[0].unitPriceSell").isEqualTo(19795.28)
                .jsonPath("$[0].minInvestment").isEqualTo(null)
                .jsonPath("$[0].source").isEqualTo("Tesouro Transparente")
                .jsonPath("$[0].stale").isEqualTo(false);
    }

    @Test
    @DisplayName("GET /quote/{symbol} - Símbolo em minúsculas é normalizado antes de chegar ao serviço")
    void shouldReturnForeignQuoteWithNormalizedSymbol() {
        when(foreignQuoteService.getQuote("VT", "US")).thenReturn(Mono.just(new ForeignQuote("VT", "US",
                new BigDecimal("161.73"), "USD", new BigDecimal("828.85"), new BigDecimal("0.72"),
                new BigDecimal("0.4472"), LocalDate.of(2026, 9, 4), "Yahoo Finance", AS_OF, false)));

        webTestClient.get()
                .uri("/api/v1/indicators/quote/vt?market=us")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.symbol").isEqualTo("VT")
                .jsonPath("$.market").isEqualTo("US")
                .jsonPath("$.price").isEqualTo(161.73)
                .jsonPath("$.currency").isEqualTo("USD")
                .jsonPath("$.priceBrl").isEqualTo(828.85)
                .jsonPath("$.changePercent").isEqualTo(0.4472)
                .jsonPath("$.date").isEqualTo("2026-09-04")
                .jsonPath("$.source").isEqualTo("Yahoo Finance")
                .jsonPath("$.stale").isEqualTo(false);

        verify(foreignQuoteService).getQuote("VT", "US");
    }

    @Test
    @DisplayName("GET /quote/{symbol} - Símbolo fora do padrão responde 400 sem chamar fonte nenhuma")
    void shouldRejectInvalidSymbol() {
        webTestClient.get()
                .uri("/api/v1/indicators/quote/PETR4")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Símbolo inválido"));

        webTestClient.get()
                .uri("/api/v1/indicators/quote/ABCDEFGHIJKLM")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest();

        verify(foreignQuoteService, never()).getQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /quote/{symbol} - Mercado que não é US responde 400")
    void shouldRejectUnsupportedMarket() {
        webTestClient.get()
                .uri("/api/v1/indicators/quote/VT?market=BR")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Mercado não suportado"));

        verify(foreignQuoteService, never()).getQuote(anyString(), anyString());
    }

    @Test
    @DisplayName("GET /quote/{symbol} - Papel que nenhuma fonte conhece responde 404 em ProblemDetail")
    void shouldReturnNotFoundForUnknownSymbol() {
        when(foreignQuoteService.getQuote("XPTO", "US")).thenReturn(Mono.empty());

        webTestClient.get()
                .uri("/api/v1/indicators/quote/XPTO")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken("teste@economize.app");
    }
}
