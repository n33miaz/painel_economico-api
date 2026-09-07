package br.com.economize.service.provider.fallback;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.dto.Indicator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * CoinGecko (API pública, sem chave, ~30 req/min): cripto em BRL com variação
 * de 24h. Única alternativa para as cripto da Home e para o histórico delas.
 *
 * <p>
 * Os ids da CoinGecko são nomes ("bitcoin"), não tickers; a tabela abaixo
 * cobre as moedas que a AwesomeAPI devolve no /all para o item continuar com o
 * mesmo id ({@code crypto_BTC}) e o app não perder favorito nem gráfico.
 */
@Component
public class CoinGeckoSource implements FallbackQuoteSource {

    public static final String SOURCE = "CoinGecko";

    /** ticker → id da CoinGecko, na ordem em que a Home os mostra. */
    private static final Map<String, String> COIN_IDS = new LinkedHashMap<>();
    static {
        COIN_IDS.put("BTC", "bitcoin");
        COIN_IDS.put("ETH", "ethereum");
        COIN_IDS.put("XRP", "ripple");
        COIN_IDS.put("LTC", "litecoin");
        COIN_IDS.put("DOGE", "dogecoin");
    }

    private final WebClient webClient;
    private final String baseUrl;

    @Autowired
    public CoinGeckoSource(WebClient webClient, MarketSourcesProperties properties) {
        this(webClient, properties.getCoingeckoUrl());
    }

    CoinGeckoSource(WebClient webClient, String baseUrl) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
    }

    /** Códigos que esta fonte sabe cotar — é também a lista de "o que é cripto" do histórico. */
    public static boolean supports(String code) {
        return code != null && COIN_IDS.containsKey(code.toUpperCase(Locale.ROOT));
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public Mono<List<Indicator>> fetch() {
        return webClient.get()
                .uri(baseUrl + "/simple/price?ids=" + String.join(",", COIN_IDS.values())
                        + "&vs_currencies=brl&include_24hr_change=true&include_last_updated_at=true")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(CoinGeckoSource::parse);
    }

    /**
     * Série diária em BRL para o gráfico, no mesmo shape do {@code /daily} da
     * AwesomeAPI (mais recente primeiro). A CoinGecko devolve pontos horários
     * até 90 dias; aqui viram um por dia UTC, com a máxima do dia em
     * {@code high}, que é o que o gráfico desenha.
     */
    public Mono<List<HistoricalDataPoint>> history(String code, int days) {
        String coinId = COIN_IDS.get(code.toUpperCase(Locale.ROOT));
        if (coinId == null) {
            return Mono.just(Collections.emptyList());
        }
        return webClient.get()
                .uri(baseUrl + "/coins/" + coinId + "/market_chart?vs_currency=brl&days=" + days)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(CoinGeckoSource::parseHistory);
    }

    /**
     * Shape: {@code {"bitcoin":{"brl":409140,"brl_24h_change":0.247,"last_updated_at":1788703290}}}.
     */
    static List<Indicator> parse(JsonNode root) {
        List<Indicator> result = new ArrayList<>();
        COIN_IDS.forEach((code, coinId) -> {
            JsonNode coin = root.get(coinId);
            if (coin == null || !coin.path("brl").isNumber()) {
                return;
            }
            BigDecimal price = coin.get("brl").decimalValue();
            BigDecimal variation = coin.path("brl_24h_change").isNumber()
                    ? coin.get("brl_24h_change").decimalValue().setScale(4, RoundingMode.HALF_UP)
                    : null;
            Instant asOf = coin.path("last_updated_at").isNumber()
                    ? Instant.ofEpochSecond(coin.get("last_updated_at").asLong())
                    : Instant.now();
            result.add(CurrencyNames.crypto(code, price, variation, SOURCE, asOf));
        });
        return result;
    }

    /** Shape: {@code {"prices":[[1788098400000, 409620.33], ...]}} (época em milissegundos). */
    static List<HistoricalDataPoint> parseHistory(JsonNode root) {
        JsonNode prices = root.path("prices");
        if (!prices.isArray()) {
            return List.of();
        }
        TreeMap<LocalDate, HistoricalDataPoint> byDay = new TreeMap<>();
        for (JsonNode point : prices) {
            if (!point.isArray() || point.size() < 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                continue;
            }
            Instant at = Instant.ofEpochMilli(point.get(0).asLong());
            BigDecimal price = point.get(1).decimalValue().setScale(2, RoundingMode.HALF_UP);
            LocalDate day = at.atOffset(ZoneOffset.UTC).toLocalDate();
            HistoricalDataPoint current = byDay.get(day);
            if (current == null || current.getHigh().compareTo(price) < 0) {
                HistoricalDataPoint high = new HistoricalDataPoint();
                high.setTimestamp(String.valueOf(at.getEpochSecond()));
                high.setHigh(price);
                byDay.put(day, high);
            }
        }
        return new ArrayList<>(byDay.descendingMap().values());
    }
}
