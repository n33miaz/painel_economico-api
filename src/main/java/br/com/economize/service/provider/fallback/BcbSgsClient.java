package br.com.economize.service.provider.fallback;

import br.com.economize.config.MarketSourcesProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente do SGS (Sistema Gerenciador de Séries Temporais) do Banco Central:
 * {@code /bcdata.sgs.{série}/dados/ultimos/{n}?formato=json}, sem chave.
 *
 * <p>
 * É a fonte dos indicadores macro (CDI, Selic, IPCA, poupança, IGP-M) e o
 * fallback do histórico de câmbio. As séries de câmbio usadas são as de VENDA,
 * fim de período, conferidas contra o boletim PTAX de fechamento de 04/09/2026
 * — o par compra/venda de cada moeda tem ids consecutivos e a ordem não é a
 * mesma para todas, por isso a conferência (CAD é 21635 e AUD 21633, não o
 * contrário que a sequência alfabética sugeriria).
 *
 * <p>
 * Série desconhecida responde 404 com página HTML, não JSON: cai como erro e o
 * chamador decide (o macro pula a série; o histórico vai ao snapshot).
 */
@Component
public class BcbSgsClient {

    public static final String SOURCE = "Banco Central (SGS)";

    /** Câmbio X→BRL, venda, fim de período, diário. */
    public static final int USD_SELL = 1;
    public static final int EUR_SELL = 21619;
    public static final int JPY_SELL = 21621;
    public static final int GBP_SELL = 21623;
    public static final int CHF_SELL = 21625;
    public static final int CNY_SELL = 21627;
    public static final int AUD_SELL = 21633;
    public static final int CAD_SELL = 21635;

    private static final DateTimeFormatter SGS_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Um ponto da série: a data de referência e o valor, como o BCB os publica. */
    public record Point(LocalDate date, BigDecimal value) {
    }

    private final WebClient webClient;
    private final String baseUrl;

    @Autowired
    public BcbSgsClient(WebClient webClient, MarketSourcesProperties properties) {
        this(webClient, properties.getBcbSgsUrl());
    }

    BcbSgsClient(WebClient webClient, String baseUrl) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
    }

    /** Os últimos {@code count} pontos da série, na ordem em que o BCB os devolve (cronológica). */
    public Mono<List<Point>> lastValues(int seriesId, int count) {
        return webClient.get()
                .uri(baseUrl + "/bcdata.sgs." + seriesId + "/dados/ultimos/" + count + "?formato=json")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(BcbSgsClient::parse);
    }

    /** Shape: {@code [{"data":"04/09/2026","valor":"5.1253"}, ...]}; algumas séries trazem {@code dataFim}, ignorado. */
    static List<Point> parse(JsonNode root) {
        if (!root.isArray()) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (JsonNode node : root) {
            String date = node.path("data").asText(null);
            String value = node.path("valor").asText(null);
            if (date == null || value == null || value.isBlank()) {
                continue;
            }
            try {
                points.add(new Point(LocalDate.parse(date, SGS_DATE), new BigDecimal(value.trim())));
            } catch (RuntimeException e) {
                // ponto ilegível não derruba a série inteira
            }
        }
        return points;
    }
}
