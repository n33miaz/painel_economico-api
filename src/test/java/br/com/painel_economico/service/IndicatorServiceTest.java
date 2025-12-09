package br.com.painel_economico.service;

import br.com.painel_economico.dto.Indicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private IndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        indicatorService = new IndicatorService(webClient);
    }

    @Test
    @DisplayName("Deve retornar lista de indicadores enriquecida com ID e Type")
    @SuppressWarnings("unchecked") 
    void shouldReturnEnrichedIndicators() {
        // prepara dados mockados
        Indicator mockCurrency = new Indicator();
        mockCurrency.setCode("USD");
        mockCurrency.setName("Dólar Americano");
        mockCurrency.setBuy(new BigDecimal("5.50"));

        Map<String, Indicator> apiResponse = new HashMap<>();
        apiResponse.put("USD", mockCurrency);

        // configura o comportamento do mock
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        // tratamento de erro
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        // corpo da resposta
        when(responseSpec.bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
        }))
                .thenReturn(Mono.just(apiResponse));

        // executa e verifica
        Mono<List<Indicator>> result = indicatorService.getAllIndicators();

        StepVerifier.create(result)
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    Indicator ind = indicators.get(0);

                    // verifica se o enriquecimento funcionou
                    assertEquals("currency_USD", ind.getId());
                    assertEquals("currency", ind.getType());
                    assertEquals("USD", ind.getCode());
                })
                .verifyComplete();
    }
}