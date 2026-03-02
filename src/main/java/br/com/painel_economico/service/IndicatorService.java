package br.com.painel_economico.service;

import br.com.painel_economico.dto.HistoricalDataPoint;
import br.com.painel_economico.dto.Indicator;
import br.com.painel_economico.service.provider.MarketDataProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class IndicatorService {

        private final List<MarketDataProvider> dataProviders;
        private final WebClient webClient;

        @Value("${awesome.api.url}")
        private String awesomeApiUrl;

        public IndicatorService(List<MarketDataProvider> dataProviders, WebClient webClient) {
                this.dataProviders = dataProviders;
                this.webClient = webClient;
        }

        @Cacheable("indicators")
        @CircuitBreaker(name = "indicators", fallbackMethod = "getAllIndicatorsFallback")
        public Mono<List<Indicator>> getAllIndicators() {
                log.debug("Iniciando orquestração de dados com {} provedores", dataProviders.size());

                return Flux.fromIterable(dataProviders)
                                .flatMap(MarketDataProvider::fetchDefaultIndicators)
                                .reduce(new java.util.ArrayList<Indicator>(), (acc, list) -> {
                                        acc.addAll(list);
                                        return acc;
                                });
        }

        public Mono<List<Indicator>> getAllIndicatorsFallback(Throwable t) {
                log.error("Circuit Breaker aberto. Retornando lista vazia. Erro: {}", t.getMessage());
                return Mono.just(Collections.emptyList());
        }

        // Busca dinâmica
        public Mono<List<Indicator>> searchIndicators(String query) {
                log.info("Buscando dinamicamente pelo ativo: {}", query);
                return Flux.fromIterable(dataProviders)
                                .flatMap(provider -> provider.searchIndicator(query))
                                .reduce(new java.util.ArrayList<Indicator>(), (acc, list) -> {
                                        acc.addAll(list);
                                        return acc;
                                });
        }

        @Cacheable("historical")
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                if (currencyCode == null || currencyCode.length() < 3) {
                        return Mono.error(new IllegalArgumentException("Código de moeda inválido"));
                }

                String cleanCode = currencyCode.replace("currency_", "").replace("crypto_", "");
                String historicalApiUrl = String.format("/daily/%s-BRL/%d", cleanCode, days);

                return webClient.get()
                                .uri(awesomeApiUrl + historicalApiUrl)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError,
                                                response -> Mono.error(new RuntimeException("Erro API Histórico")))
                                .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                                })
                                .onErrorResume(e -> Mono.just(Collections.emptyList()));
        }

        public Mono<BigDecimal> calculateConversion(String currencyCode, BigDecimal amountInBrl) {
                return getAllIndicators()
                                .flatMap(indicators -> Mono.justOrEmpty(indicators.stream()
                                                .filter(i -> i.getCode() != null
                                                                && i.getCode().equalsIgnoreCase(currencyCode))
                                                .findFirst()))
                                .switchIfEmpty(Mono.error(
                                                new IllegalArgumentException("Moeda não encontrada: " + currencyCode)))
                                .map(indicator -> {
                                        BigDecimal sellPrice = indicator.getBuy();
                                        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) == 0) {
                                                throw new IllegalArgumentException(
                                                                "Cotação indisponível para conversão.");
                                        }
                                        return amountInBrl.divide(sellPrice, 2, RoundingMode.HALF_EVEN);
                                });
        }
}