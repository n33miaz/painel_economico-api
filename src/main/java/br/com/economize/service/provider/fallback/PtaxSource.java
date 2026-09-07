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
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PTAX do Banco Central (Olinda/OData): o boletim oficial do dólar, terceira
 * opção para o USD quando AwesomeAPI e Frankfurter falharam.
 *
 * <p>
 * Usa {@code CotacaoDolarPeriodo} e não {@code CotacaoDolarDia}: este devolve
 * {@code value: []} em fim de semana e feriado (conferido num domingo), e o
 * fallback é justamente para o dia em que nada mais funciona. Uma janela de dez
 * dias ordenada do mais recente para o mais antigo, com {@code $top=2}, traz o
 * último fechamento e o anterior — de onde sai a variação — em uma chamada só.
 * Só USD: o boletim das outras moedas existe, mas a Frankfurter já as cobre.
 *
 * <p>
 * A URL vai montada à mão ({@link URI#create}) porque o OData exige aspas,
 * arrobas e cifrões literais que o codificador de template do WebClient
 * transformaria em percentuais.
 */
@Component
public class PtaxSource implements FallbackQuoteSource {

    public static final String SOURCE = "BCB PTAX";

    private static final ZoneId BCB_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter ODATA_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final int WINDOW_DAYS = 10;

    private final WebClient webClient;
    private final String baseUrl;
    private final Clock clock;

    @Autowired
    public PtaxSource(WebClient webClient, MarketSourcesProperties properties) {
        this(webClient, properties.getPtaxUrl(), Clock.systemUTC());
    }

    PtaxSource(WebClient webClient, String baseUrl, Clock clock) {
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
        LocalDate today = LocalDate.now(clock.withZone(BCB_ZONE));
        String uri = baseUrl + "/CotacaoDolarPeriodo(dataInicial=@dataInicial,dataFinalCotacao=@dataFinalCotacao)"
                + "?@dataInicial=%27" + ODATA_DATE.format(today.minusDays(WINDOW_DAYS)) + "%27"
                + "&@dataFinalCotacao=%27" + ODATA_DATE.format(today) + "%27"
                + "&$format=json&$orderby=dataHoraCotacao%20desc&$top=2";
        return webClient.get()
                .uri(URI.create(uri))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(PtaxSource::parse);
    }

    /**
     * Shape: {@code {"value":[{"cotacaoCompra":5.1247,"cotacaoVenda":5.1253,"dataHoraCotacao":"2026-09-04 13:03:59.556874"}]}}.
     * A ordem não é presumida: o mais recente é escolhido aqui.
     */
    static List<Indicator> parse(JsonNode root) {
        JsonNode values = root.path("value");
        if (!values.isArray() || values.isEmpty()) {
            return List.of();
        }
        List<JsonNode> quotes = new ArrayList<>();
        values.forEach(quotes::add);
        quotes.sort(Comparator.comparing((JsonNode node) -> node.path("dataHoraCotacao").asText()).reversed());

        JsonNode latest = quotes.get(0);
        BigDecimal buy = decimal(latest.get("cotacaoCompra"));
        BigDecimal sell = decimal(latest.get("cotacaoVenda"));
        if (buy == null || sell == null) {
            return List.of();
        }
        BigDecimal variation = null;
        if (quotes.size() > 1) {
            BigDecimal previousSell = decimal(quotes.get(1).get("cotacaoVenda"));
            if (previousSell != null && previousSell.signum() > 0) {
                variation = sell.divide(previousSell, MathContext.DECIMAL64)
                        .subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
            }
        }
        return List.of(CurrencyNames.fiat("USD", buy, sell, variation, SOURCE, asOf(latest)));
    }

    private static Instant asOf(JsonNode quote) {
        String raw = quote.path("dataHoraCotacao").asText(null);
        if (raw == null) {
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(raw, QUOTE_TIME).atZone(BCB_ZONE).toInstant();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }
}
