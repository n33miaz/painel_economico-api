package br.com.painel_economico.service.provider;

import br.com.painel_economico.dto.Indicator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BrapiProvider implements MarketDataProvider {

    private final WebClient webClient;
    private final String brapiApiUrl;
    private final String brapiToken;

    private static final String DEFAULT_TICKERS = "PETR4,VALE3,ITUB4,MXRF11,BOVA11,IVVB11,^BVSP";

    public BrapiProvider(WebClient webClient,
            @Value("${brapi.api.url}") String brapiApiUrl,
            @Value("${brapi.api.token}") String brapiToken) {
        this.webClient = webClient;
        this.brapiApiUrl = brapiApiUrl;
        this.brapiToken = brapiToken;
    }

    @Override
    public String getProviderName() {
        return "Brapi (B3 & Indexes)";
    }

    @Override
    public Mono<List<Indicator>> fetchDefaultIndicators() {
        return fetchFromBrapi(DEFAULT_TICKERS);
    }

    @Override
    public Mono<List<Indicator>> searchIndicator(String query) {
        // Permite buscar qualquer ticker na B3 dinamicamente
        return fetchFromBrapi(query.toUpperCase());
    }

    private Mono<List<Indicator>> fetchFromBrapi(String tickers) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("brapi.dev")
                        .path("/api/quote/" + tickers)
                        .queryParam("token", brapiToken)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseBrapiResponse)
                .onErrorResume(e -> {
                    log.error("Erro ao buscar dados na Brapi para tickers [{}]: {}", tickers, e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    @SuppressWarnings("unchecked")
    private List<Indicator> parseBrapiResponse(Map<String, Object> response) {
        List<Indicator> stocks = new ArrayList<>();
        if (response.containsKey("results")) {
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            for (Map<String, Object> item : results) {
                Indicator ind = new Indicator();
                String symbol = (String) item.get("symbol");

                boolean isIndex = symbol.startsWith("^");
                ind.setId(isIndex ? "index_" + symbol : "stock_" + symbol);
                ind.setType(isIndex ? "index" : "stock");
                ind.setCode(symbol);
                ind.setName((String) item.get("shortName"));

                Object priceObj = item.get("regularMarketPrice");
                if (priceObj instanceof Number) {
                    ind.setBuy(BigDecimal.valueOf(((Number) priceObj).doubleValue()));
                }

                Object changeObj = item.get("regularMarketChangePercent");
                if (changeObj instanceof Number) {
                    ind.setVariation(BigDecimal.valueOf(((Number) changeObj).doubleValue()));
                }

                stocks.add(ind);
            }
        }
        return stocks;
    }
}