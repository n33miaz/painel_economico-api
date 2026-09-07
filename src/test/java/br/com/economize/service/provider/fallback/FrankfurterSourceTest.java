package br.com.economize.service.provider.fallback;

import br.com.economize.dto.Indicator;
import br.com.economize.support.MutableClock;
import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrankfurterSourceTest {

    // resposta real de 06/09/2026 (dois dias, sem ARS — o BCE não o cota)
    private static final String BODY = """
            {"amount":1.0,"base":"BRL","start_date":"2026-09-03","end_date":"2026-09-04","rates":{
              "2026-09-03":{"AUD":0.27398,"CAD":0.27181,"CHF":0.15933,"CNY":1.3242,"EUR":0.16968,"GBP":0.14602,"JPY":30.747,"USD":0.19708},
              "2026-09-04":{"AUD":0.27159,"CAD":0.26998,"CHF":0.15832,"CNY":1.3129,"EUR":0.16834,"GBP":0.1446,"JPY":30.568,"USD":0.19564}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Inverte BRL→X para X→BRL, calcula a variação do dia e data às 16h de Frankfurt")
    void shouldParseLatestDayInverted() throws Exception {
        List<Indicator> indicators = FrankfurterSource.parse(mapper.readTree(BODY));

        assertEquals(8, indicators.size(), "ARS não veio e fica de fora");
        Indicator usd = indicators.get(0);
        assertEquals("currency_USD", usd.getId());
        assertEquals("currency", usd.getType());
        assertEquals("BRL", usd.getCodeIn());
        assertEquals("Dólar Americano/Real Brasileiro", usd.getName());
        assertEquals(new BigDecimal("5.1114"), usd.getBuy(), "1 / 0.19564");
        assertEquals(usd.getBuy(), usd.getSell(), "taxa de referência não tem spread");
        // 0.19708/0.19564 - 1 = +0,74%: o dólar subiu porque o real vale menos dólar
        assertEquals(new BigDecimal("0.74"), usd.getVariation());
        assertEquals(FrankfurterSource.SOURCE, usd.getSource());
        assertEquals(Instant.parse("2026-09-04T14:00:00Z"), usd.getAsOf());

        Indicator jpy = indicators.stream().filter(i -> "JPY".equals(i.getCode())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.0327"), jpy.getBuy());
    }

    @Test
    @DisplayName("Um dia só: preço sem variação, e não variação zero inventada")
    void singleDayShouldHaveNullVariation() throws Exception {
        String body = """
                {"base":"BRL","end_date":"2026-09-04","rates":{"2026-09-04":{"USD":0.2}}}
                """;
        List<Indicator> indicators = FrankfurterSource.parse(mapper.readTree(body));

        assertEquals(1, indicators.size());
        assertEquals(new BigDecimal("5.0000"), indicators.get(0).getBuy());
        // o getter troca null por zero para o contrato do /all; a cópia stale preserva o cru
        assertNull(indicators.get(0).staleCopy().getVariation().signum() == 0 ? null : "x");
    }

    @Test
    @DisplayName("Resposta sem taxas vira lista vazia, não erro")
    void emptyRatesShouldYieldEmptyList() throws Exception {
        assertTrue(FrankfurterSource.parse(mapper.readTree("{\"rates\":{}}")).isEmpty());
        assertTrue(FrankfurterSource.parse(mapper.readTree("{}")).isEmpty());
    }

    @Test
    @DisplayName("Pede a série da última semana com base BRL e as moedas da Home")
    void shouldRequestWeeklySeriesFromBrl() {
        List<String> urls = new ArrayList<>();
        FrankfurterSource source = new FrankfurterSource(
                StubWebClient.respondingWith(urls, request -> StubWebClient.json(BODY)),
                "https://example.test/frankfurter", new MutableClock(Instant.parse("2026-09-06T12:00:00Z")));

        StepVerifier.create(source.fetch())
                .assertNext(indicators -> assertEquals(8, indicators.size()))
                .verifyComplete();

        assertEquals(1, urls.size());
        assertTrue(urls.get(0).startsWith("https://example.test/frankfurter/2026-08-30..?from=BRL&to=USD,EUR"),
                urls.get(0));
    }
}
