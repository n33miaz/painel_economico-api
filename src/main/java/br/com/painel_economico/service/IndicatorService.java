package br.com.painel_economico.service;

import br.com.painel_economico.dto.HistoricalDataPointDTO;
import br.com.painel_economico.dto.IndicatorDTO;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpStatusCode;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class IndicatorService {
        private final WebClient webClient;
        private final String awesomeApiUrl = "https://economia.awesomeapi.com.br/json/all";

        public IndicatorService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("indicators")
        public List<IndicatorDTO> getAllIndicators() {
                Map<String, IndicatorDTO> responseMap = webClient.get()
                                .uri(awesomeApiUrl)
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
                                .bodyToMono(new ParameterizedTypeReference<Map<String, IndicatorDTO>>() {
                                })
                                .block();

                if (responseMap == null) {
                        return List.of();
                }

                return new ArrayList<>(responseMap.values());
        }

        @Cacheable("historical")
        public List<HistoricalDataPointDTO> getHistoricalData(String currencyCode, int days) {
                String historicalApiUrl = String.format("https://economia.awesomeapi.com.br/json/daily/%s-BRL/%d",
                                currencyCode,
                                days);

                return webClient.get()
                                .uri(historicalApiUrl)
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

                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPointDTO>>() {
                                })
                                .block();
        }
}