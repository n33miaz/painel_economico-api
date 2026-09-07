package br.com.economize.service.provider.fallback;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.Indicator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * Frankfurter (api.frankfurter.app): taxas de referência do BCE, sem chave e
 * sem cota publicada. Primeira alternativa para as moedas fiat.
 *
 * <p>
 * Pede a SÉRIE dos últimos dias em vez do {@code /latest}: custa a mesma
 * requisição e traz o dia anterior, de onde sai a variação diária que o
 * {@code /latest} não dá. O BCE cota BRL→X (quanto vale 1 real em dólar); a
 * Home mostra X→BRL, então o valor é invertido. ARS não existe no BCE — a API
 * simplesmente o ignora no {@code to=}, e aqui ele fica de fora.
 *
 * <p>
 * {@code asOf} é a data de referência às 16h de Frankfurt, hora em que o BCE
 * publica; compra e venda são o mesmo número porque taxa de referência não tem
 * spread.
 */
@Component
public class FrankfurterSource implements FallbackQuoteSource {

    public static final String SOURCE = "Frankfurter (BCE)";

    static final List<String> CODES = List.of("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY", "ARS");
    private static final ZoneId ECB_ZONE = ZoneId.of("Europe/Berlin");
    private static final int PUBLICATION_HOUR = 16;
    private static final int LOOKBACK_DAYS = 7;

    private final WebClient webClient;
    private final String baseUrl;
    private final Clock clock;

    @Autowired
    public FrankfurterSource(WebClient webClient, MarketSourcesProperties properties) {
        this(webClient, properties.getFrankfurterUrl(), Clock.systemUTC());
    }

    FrankfurterSource(WebClient webClient, String baseUrl, Clock clock) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public Mono<List<Indicator>> fetch() {
        LocalDate start = LocalDate.now(clock).minusDays(LOOKBACK_DAYS);
        return webClient.get()
                .uri(baseUrl + "/" + start + "..?from=BRL&to=" + String.join(",", CODES))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(FrankfurterSource::parse);
    }

    /**
     * Shape: {@code {"base":"BRL","end_date":"2026-09-04","rates":{"2026-09-03":{"USD":0.197},...}}}.
     * Método puro para o parse ser testável com JSON fixo.
     */
    static List<Indicator> parse(JsonNode root) {
        JsonNode rates = root.path("rates");
        if (!rates.isObject() || rates.isEmpty()) {
            return List.of();
        }
        TreeMap<LocalDate, JsonNode> byDate = new TreeMap<>();
        for (Iterator<String> it = rates.fieldNames(); it.hasNext();) {
            String date = it.next();
            byDate.put(LocalDate.parse(date), rates.get(date));
        }
        LocalDate latestDate = byDate.lastKey();
        JsonNode latest = byDate.get(latestDate);
        JsonNode previous = byDate.size() > 1 ? byDate.lowerEntry(latestDate).getValue() : null;
        Instant asOf = latestDate.atTime(PUBLICATION_HOUR, 0).atZone(ECB_ZONE).toInstant();

        List<Indicator> result = new ArrayList<>();
        for (String code : CODES) {
            BigDecimal brlToX = decimal(latest.get(code));
            if (brlToX == null || brlToX.signum() <= 0) {
                continue;
            }
            BigDecimal price = invert(brlToX);
            BigDecimal variation = null;
            BigDecimal previousBrlToX = previous != null ? decimal(previous.get(code)) : null;
            if (previousBrlToX != null && previousBrlToX.signum() > 0) {
                // X→BRL = 1/(BRL→X): a variação do inverso é anterior/atual - 1
                variation = previousBrlToX.divide(brlToX, MathContext.DECIMAL64)
                        .subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
            }
            result.add(CurrencyNames.fiat(code, price, price, variation, SOURCE, asOf));
        }
        return result;
    }

    private static BigDecimal invert(BigDecimal rate) {
        return BigDecimal.ONE.divide(rate, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }
}
