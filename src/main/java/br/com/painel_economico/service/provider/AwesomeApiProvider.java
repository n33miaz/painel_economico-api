package br.com.painel_economico.service.provider;

import br.com.painel_economico.dto.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AwesomeApiProvider implements MarketDataProvider {

    private final WebClient webClient;
    private final String awesomeApiUrl;

    public AwesomeApiProvider(WebClient webClient, @Value("${awesome.api.url}") String awesomeApiUrl) {
        this.webClient = webClient;
        this.awesomeApiUrl = awesomeApiUrl;
    }

    @Override
    public String getProviderName() {
        return "AwesomeAPI (Currencies)";
    }

    @Override
    public Mono<List<Indicator>> fetchDefaultIndicators() {
        return webClient.get()
                .uri(awesomeApiUrl + "/all")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleApiError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
                })
                .map(responseMap -> responseMap.entrySet().stream()
                        .map(entry -> enrichIndicatorData(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toList()))
                .onErrorResume(e -> {
                    log.error("Erro ao buscar moedas na AwesomeAPI: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @Override
    public Mono<List<Indicator>> searchIndicator(String query) {
        // AwesomeAPI não tem busca dinâmica simples por texto, retorna vazio por padrão
        return Mono.just(Collections.emptyList());
    }

    private Indicator enrichIndicatorData(String key, Indicator indicator) {
        boolean isCrypto = key.equalsIgnoreCase("BTC") || key.equalsIgnoreCase("ETH") ||
                key.equalsIgnoreCase("XRP") || key.equalsIgnoreCase("LTC");

        if (isCrypto) {
            indicator.setId("crypto_" + key);
            indicator.setType("crypto");
            switch (key.toUpperCase()) {
                case "BTC" -> indicator.setName("Bitcoin");
                case "ETH" -> indicator.setName("Ethereum");
                case "XRP" -> indicator.setName("XRP");
                case "LTC" -> indicator.setName("Litecoin");
            }
        } else {
            indicator.setId("currency_" + key);
            indicator.setType("currency");
            if (key.endsWith("T")) {
                indicator.setName(indicator.getName() + " (Turismo)");
            }
        }
        return indicator;
    }

    private Mono<? extends Throwable> handleApiError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new WebClientResponseException(
                        response.statusCode().value(), "Erro AwesomeAPI: " + errorBody,
                        response.headers().asHttpHeaders(), errorBody.getBytes(), null)));
    }
}