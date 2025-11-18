package br.com.painel_economico.service;

import br.com.painel_economico.dto.IndicatorDTO;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.HttpStatusCode;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IndicatorService {

    private final WebClient webClient;
    private final String awesomeApiUrl = "https://economia.awesomeapi.com.br/json/all";

    public IndicatorService(WebClient webClient) {
        this.webClient = webClient;
    }

    public List<IndicatorDTO> getAllIndicators() {
        Map<String, IndicatorDTO> responseMap = webClient.get()
                .uri(awesomeApiUrl)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .flatMap(errorBody -> Mono.error(
                                new WebClientResponseException(
                                        response.statusCode().value(),
                                        "Erro na API de Indicadores: " + errorBody,
                                        response.headers().asHttpHeaders(),
                                        errorBody.getBytes(),
                                        null))))
                .bodyToMono(Map.class)
                .block();

        if (responseMap == null) {
            return List.of();
        }

        return responseMap.values().stream()
                .map(value -> (IndicatorDTO) value)
                .collect(Collectors.toList());
    }
}