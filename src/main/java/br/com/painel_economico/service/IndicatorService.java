package br.com.painel_economico.service;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import reactor.core.publisher.Mono;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpStatusCode;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IndicatorService {
        private final WebClient webClient;
        private final String awesomeApiUrl = "https://economia.awesomeapi.com.br/json";

        public IndicatorService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("indicators")
        public Mono<List<Indicator>> getAllIndicators() {
                return webClient.get()
                                .uri(awesomeApiUrl + "/all")
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                                                .flatMap(errorBody -> Mono.error(
                                                                new WebClientResponseException(
                                                                                response.statusCode().value(),
                                                                                "Erro na API de Indicadores: "
                                                                                                + errorBody,
                                                                                response.headers().asHttpHeaders(),
                                                                                errorBody.getBytes(),
                                                                                null))))
                                .bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
                                })
                                .map(responseMap -> {
                                        return responseMap.values().stream()
                                                        .map(this::enrichIndicatorData)
                                                        .collect(Collectors.toList());
                                })
                                .defaultIfEmpty(Collections.emptyList());
        }

        private Indicator enrichIndicatorData(Indicator indicator) {
                if (indicator.getCode() != null) {
                        indicator.setId("currency_" + indicator.getCode());
                        indicator.setType("currency");
                } else {
                        indicator.setId("index_" + indicator.getName().replaceAll("\\s+", ""));
                        indicator.setType("index");
                }
                return indicator;
        }

        @Cacheable("historical")
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                String historicalApiUrl = String.format("/daily/%s-BRL/%d", currencyCode, days);

                return webClient.get()
                                .uri(awesomeApiUrl + historicalApiUrl)
                                .retrieve()
                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                                })
                                .onErrorResume(e -> {
                                        System.err.println("Erro ao buscar histórico: " + e.getMessage());
                                        return Mono.just(Collections.emptyList());
                                });
        }
}