package br.com.economize.service;

import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.dto.Indicator;
import br.com.economize.service.provider.MarketDataProvider;
import br.com.economize.service.provider.MarketSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {

    @Mock
    private MarketDataProvider mockProvider;

    @Mock
    private HistoricalDataService historicalDataService;

    private MarketSnapshotStore snapshotStore;

    private IndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        // Injetamos uma lista contendo o nosso provider mockado
        indicatorService = new IndicatorService(List.of(mockProvider), snapshotStore, historicalDataService);
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

    @Test
    @DisplayName("Fallback do circuit breaker deve servir snapshot stale quando existir")
    void fallbackShouldServeStaleSnapshot() {
        Indicator usd = new Indicator();
        usd.setCode("USD");
        usd.setBuy(new BigDecimal("5.55"));
        snapshotStore.save("awesome:all", List.of(usd));

        StepVerifier.create(indicatorService.getAllIndicatorsFallback(new RuntimeException("circuito aberto")))
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("USD", indicators.get(0).getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Fallback do circuit breaker deve retornar lista vazia sem snapshot stale")
    void fallbackShouldReturnEmptyWithoutStaleSnapshot() {
        StepVerifier.create(indicatorService.getAllIndicatorsFallback(new RuntimeException("circuito aberto")))
                .assertNext(indicators -> assertEquals(0, indicators.size()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Histórico é delegado ao serviço que guarda orçamento, fallback e snapshot")
    void historicalDataShouldBeDelegated() {
        HistoricalDataPoint point = new HistoricalDataPoint();
        point.setTimestamp("1788557375");
        point.setHigh(new BigDecimal("5.13"));
        when(historicalDataService.getHistoricalData("USD", 7)).thenReturn(Mono.just(List.of(point)));

        StepVerifier.create(indicatorService.getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(1, points.size()))
                .verifyComplete();

        verify(historicalDataService).getHistoricalData("USD", 7);
    }
}
