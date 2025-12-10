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

    private void mockWebClientResponse(Map<String, Indicator> responseBody) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
        }))
                .thenReturn(Mono.just(responseBody));
    }

    @Test
    @DisplayName("Deve converter BRL para moeda estrangeira corretamente")
    void shouldConvertCurrencyCorrectly() {
        Indicator usd = new Indicator();
        usd.setCode("USD");
        usd.setSell(new BigDecimal("5.00"));

        Map<String, Indicator> apiResponse = new HashMap<>();
        apiResponse.put("USD", usd);

        mockWebClientResponse(apiResponse);

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
        mockWebClientResponse(new HashMap<>());

        Mono<BigDecimal> result = indicatorService.calculateConversion("XYZ", new BigDecimal("100"));

        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}