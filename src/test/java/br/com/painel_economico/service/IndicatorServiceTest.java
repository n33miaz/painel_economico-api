package br.com.painel_economico.service;

import br.com.painel_economico.dto.Indicator;
import br.com.painel_economico.service.provider.MarketDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {

    @Mock
    private MarketDataProvider mockProvider;

    @Mock
    private WebClient webClient;

    private IndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        // Injetamos uma lista contendo o nosso provider mockado
        indicatorService = new IndicatorService(List.of(mockProvider), webClient);
    }

    @Test
    @DisplayName("Deve converter BRL para moeda estrangeira corretamente")
    void shouldConvertCurrencyCorrectly() {
        Indicator usd = new Indicator();
        usd.setCode("USD");
        usd.setBuy(new BigDecimal("5.00")); 

        when(mockProvider.fetchDefaultIndicators()).thenReturn(Mono.just(List.of(usd)));

        Mono<BigDecimal> result = indicatorService.calculateConversion("USD", new BigDecimal("100.00"));

        StepVerifier.create(result)
                .assertNext(value -> {
                    assertEquals(new BigDecimal("20.00"), value);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve retornar erro ao tentar converter moeda inexistente")
    void shouldReturnErrorForInvalidCurrency() {
        when(mockProvider.fetchDefaultIndicators()).thenReturn(Mono.just(Collections.emptyList()));

        Mono<BigDecimal> result = indicatorService.calculateConversion("XYZ", new BigDecimal("100"));

        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}