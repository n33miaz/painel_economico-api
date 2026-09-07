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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtaxSourceTest {

    // dois fechamentos reais, propositalmente fora de ordem
    private static final String BODY = """
            {"@odata.context":"...#_CotacaoDolarPeriodo","value":[
              {"cotacaoCompra":5.09560,"cotacaoVenda":5.09620,"dataHoraCotacao":"2026-09-03 13:04:12.001000"},
              {"cotacaoCompra":5.12470,"cotacaoVenda":5.12530,"dataHoraCotacao":"2026-09-04 13:03:59.556874"}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Escolhe o fechamento mais recente, com compra/venda e variação sobre o anterior")
    void shouldPickLatestClosing() throws Exception {
        List<Indicator> indicators = PtaxSource.parse(mapper.readTree(BODY));

        assertEquals(1, indicators.size());
        Indicator usd = indicators.get(0);
        assertEquals("currency_USD", usd.getId());
        // Por VALOR, e não por equals: o Jackson lê 5.12470 do JSON como
        // 5.1247 (mesmo número, escala 4), e o equals de BigDecimal compara
        // a escala junto
        assertEquals(0, new BigDecimal("5.12470").compareTo(usd.getBuy()));
        assertEquals(0, new BigDecimal("5.12530").compareTo(usd.getSell()));
        // 5.1253 / 5.0962 - 1 = +0,57%
        assertEquals(new BigDecimal("0.57"), usd.getVariation());
        assertEquals(PtaxSource.SOURCE, usd.getSource());
        // 13:03:59 de Brasília = 16:03:59 UTC
        assertEquals(Instant.parse("2026-09-04T16:03:59.556874Z"), usd.getAsOf());
    }

    @Test
    @DisplayName("Fim de semana sem boletim (value vazio) vira lista vazia")
    void emptyValueShouldYieldEmptyList() throws Exception {
        assertTrue(PtaxSource.parse(mapper.readTree("{\"value\":[]}")).isEmpty());
    }

    @Test
    @DisplayName("Usa o período (não o dia) com aspas e cifrões literais que o OData exige")
    void shouldRequestPeriodWithLiteralOdataSyntax() {
        List<String> urls = new ArrayList<>();
        PtaxSource source = new PtaxSource(
                StubWebClient.respondingWith(urls, request -> StubWebClient.json(BODY)),
                "https://example.test/ptax", new MutableClock(Instant.parse("2026-09-06T12:00:00Z")));

        StepVerifier.create(source.fetch()).expectNextCount(1).verifyComplete();

        String url = urls.get(0);
        assertTrue(url.startsWith("https://example.test/ptax/CotacaoDolarPeriodo(dataInicial=@dataInicial,"
                + "dataFinalCotacao=@dataFinalCotacao)?@dataInicial=%2708-27-2026%27"), url);
        assertTrue(url.contains("@dataFinalCotacao=%2709-06-2026%27"), url);
        assertTrue(url.endsWith("&$format=json&$orderby=dataHoraCotacao%20desc&$top=2"), url);
    }
}
