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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
                                .map(responseMap -> (List<Indicator>) new ArrayList<>(responseMap.values()))
                                .defaultIfEmpty(Collections.emptyList());
        }

        @Cacheable("historical")
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                String historicalApiUrl = String.format("/daily/%s-BRL/%d", currencyCode, days);

                return webClient.get()
                                .uri(awesomeApiUrl + historicalApiUrl)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                                                .flatMap(errorBody -> Mono.error(
                                                                new WebClientResponseException(
                                                                                response.statusCode().value(),
                                                                                "Erro na API de Dados Históricos: "
                                                                                                + errorBody,
                                                                                response.headers().asHttpHeaders(),
                                                                                errorBody.getBytes(),
                                                                                null))))
                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                                })
                                .defaultIfEmpty(Collections.emptyList());
        }
}