package br.com.painel_economico.service;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IndicatorService {
        private final WebClient webClient;
        private final String AWESOME_API_URL = "https://economia.awesomeapi.com.br/json";

        public IndicatorService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("indicators")
        public Mono<List<Indicator>> getAllIndicators() {
                return webClient.get()
                                .uri(AWESOME_API_URL + "/all")
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, this::handleApiError)
                                .bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
                                })
                                .map(responseMap -> responseMap.values().stream()
                                                .map(this::enrichIndicatorData)
                                                .collect(Collectors.toList()))
                                .defaultIfEmpty(Collections.emptyList());
        }

        @Cacheable("historical")
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                if (currencyCode == null || currencyCode.length() != 3) {
                        return Mono.error(new IllegalArgumentException("Código de moeda inválido"));
                }

                String historicalApiUrl = String.format("/daily/%s-BRL/%d", currencyCode, days);

                return webClient.get()
                                .uri(AWESOME_API_URL + historicalApiUrl)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, this::handleApiError)
                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                                })
                                .onErrorResume(e -> {
                                        System.err.println("Erro ao buscar histórico para " + currencyCode + ": "
                                                        + e.getMessage());
                                        return Mono.just(Collections.emptyList());
                                });
        }

        public Mono<BigDecimal> calculateConversion(String currencyCode, BigDecimal amountInBrl) {
                if (amountInBrl == null || amountInBrl.compareTo(BigDecimal.ZERO) < 0) {
                        return Mono.error(new IllegalArgumentException("Valor inválido para conversão"));
                }

                return getAllIndicators()
                                .flatMap(indicators -> Mono.justOrEmpty(indicators.stream()
                                                .filter(i -> i.getCode() != null
                                                                && i.getCode().equalsIgnoreCase(currencyCode))
                                                .findFirst()))
                                .switchIfEmpty(Mono.error(
                                                new IllegalArgumentException("Moeda não encontrada: " + currencyCode)))
                                .map(indicator -> {
                                        BigDecimal sellPrice = indicator.getSell();
                                        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) == 0) {
                                                sellPrice = indicator.getBuy();
                                        }

                                        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) == 0) {
                                                throw new IllegalArgumentException(
                                                                "Cotação indisponível para conversão.");
                                        }

                                        return amountInBrl.divide(sellPrice, 2, RoundingMode.HALF_EVEN);
                                });
        }

        private Indicator enrichIndicatorData(Indicator indicator) {
                if (indicator.getCode() != null) {
                        indicator.setId("currency_" + indicator.getCode());
                        indicator.setType("currency");
                } else {
                        String cleanName = indicator.getName() != null
                                        ? indicator.getName().replaceAll("\\s+", "")
                                        : "unknown";
                        indicator.setId("index_" + cleanName);
                        indicator.setType("index");
                }
                return indicator;
        }

        private Mono<? extends Throwable> handleApiError(ClientResponse response) {
                return response.bodyToMono(String.class)
                                .flatMap(errorBody -> Mono.error(new WebClientResponseException(
                                                response.statusCode().value(),
                                                "Erro na API Externa: " + errorBody,
                                                response.headers().asHttpHeaders(),
                                                errorBody.getBytes(),
                                                null)));
        }
}