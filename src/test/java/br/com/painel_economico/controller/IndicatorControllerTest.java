package br.com.painel_economico.controller;

import br.com.painel_economico.dto.Indicator;
import br.com.painel_economico.service.IndicatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(IndicatorController.class)
class IndicatorControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private IndicatorService indicatorService;

    @Test
    @DisplayName("GET /all - Deve retornar lista de indicadores com sucesso")
    void shouldReturnAllIndicators() {
        Indicator ind = new Indicator();
        ind.setCode("USD");
        ind.setName("Dólar");
        ind.setBuy(new BigDecimal("5.00"));

        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(ind)));

        webTestClient.get()
                .uri("/api/indicators/all")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].code").isEqualTo("USD")
                .jsonPath("$[0].buy").isEqualTo(5.00);
    }

    @Test
    @DisplayName("GET /convert - Deve realizar conversão corretamente")
    void shouldConvertCurrency() {
        BigDecimal resultValue = new BigDecimal("20.00");

        when(indicatorService.calculateConversion(eq("USD"), any(BigDecimal.class)))
                .thenReturn(Mono.just(resultValue));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/indicators/convert")
                        .queryParam("code", "USD")
                        .queryParam("amount", "100.00")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result").isEqualTo(20.00)
                .jsonPath("$.currency").isEqualTo("USD");
    }

    @Test
    @DisplayName("GET /convert - Deve retornar 400 Bad Request para moeda inválida")
    void shouldReturnBadRequestForInvalidCurrency() {
        when(indicatorService.calculateConversion(any(), any()))
                .thenReturn(Mono.error(new IllegalArgumentException("Moeda inválida")));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/indicators/convert")
                        .queryParam("code", "INVALID")
                        .queryParam("amount", "100")
                        .build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").exists();
    }
}