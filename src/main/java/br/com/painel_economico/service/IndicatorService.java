package br.com.painel_economico.service;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class IndicatorService {
        private final WebClient webClient;
        private final String AWESOME_API_URL = "https://economia.awesomeapi.com.br/json";

        public IndicatorService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("indicators")
        public Mono<List<Indicator>> getAllIndicators() {
                log.debug("Buscando todos os indicadores na AwesomeAPI");
                return webClient.get()
                                .uri(AWESOME_API_URL + "/all")
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, this::handleApiError)
                                .bodyToMono(new ParameterizedTypeReference<Map<String, Indicator>>() {
                                })
                                .map(responseMap -> responseMap.entrySet().stream()
                                                .map(entry -> enrichIndicatorData(entry.getKey(), entry.getValue()))
                                                .collect(Collectors.toList()))
                                .doOnSuccess(list -> log.debug("Indicadores recuperados: {}", list.size()))
                                .defaultIfEmpty(Collections.emptyList());
        }

        @Cacheable("historical")
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                if (currencyCode == null || currencyCode.length() < 3) {
                        log.warn("Tentativa de busca histórica com código inválido: {}", currencyCode);
                        return Mono.error(new IllegalArgumentException("Código de moeda inválido"));
                }

                String cleanCode = currencyCode.replace("currency_", "").replace("index_", "");
                String historicalApiUrl = String.format("/daily/%s-BRL/%d", cleanCode, days);

                log.debug("Buscando histórico para: {}", cleanCode);

                return webClient.get()
                                .uri(AWESOME_API_URL + historicalApiUrl)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, this::handleApiError)
                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                                })
                                .doOnError(e -> log.error("Erro ao buscar histórico para {}: {}", currencyCode,
                                                e.getMessage()))
                                .onErrorResume(e -> Mono.just(Collections.emptyList()));
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

                                        BigDecimal result = amountInBrl.divide(sellPrice, 2, RoundingMode.HALF_EVEN);
                                        log.info("Conversão realizada: {} BRL -> {} {} (Taxa: {})", amountInBrl, result,
                                                        currencyCode, sellPrice);
                                        return result;
                                });
        }

        private Indicator enrichIndicatorData(String key, Indicator indicator) {
                boolean isIndex = key.equalsIgnoreCase("IBOVESPA")
                                || key.equalsIgnoreCase("NASDAQ")
                                || key.equalsIgnoreCase("BTC")
                                || key.equalsIgnoreCase("ETH")
                                || key.equalsIgnoreCase("XRP")
                                || key.equalsIgnoreCase("LTC");

                if (isIndex) {
                        indicator.setId("index_" + key);
                        indicator.setType("index");

                        switch (key.toUpperCase()) {
                                case "BTC" -> indicator.setName("Bitcoin (Ref. Mercado)");
                                case "ETH" -> indicator.setName("Ethereum (Smart Contracts)");
                                case "XRP" -> indicator.setName("XRP (Ripple)");
                                case "LTC" -> indicator.setName("Litecoin");
                                case "IBOVESPA" -> indicator.setName("Ibovespa B3");
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
                                                response.statusCode().value(),
                                                "Erro na API Externa: " + errorBody,
                                                response.headers().asHttpHeaders(),
                                                errorBody.getBytes(),
                                                null)));
        }
}