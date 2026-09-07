package br.com.economize.service.provider.fallback;

import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.dto.Indicator;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoinGeckoSourceTest {

    // resposta real de 06/09/2026
    private static final String PRICES = """
            {"bitcoin":{"brl":409129,"brl_24h_change":0.17977152438292918,"last_updated_at":1788703290},
             "ethereum":{"brl":12786.27,"brl_24h_change":1.5786866229471694,"last_updated_at":1788703290},
             "ripple":{"brl":7.26,"brl_24h_change":0.18180708048560423,"last_updated_at":1788703290},
             "litecoin":{"brl":279.74,"brl_24h_change":1.179135247420005,"last_updated_at":1788703290}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Mapeia ids da CoinGecko para os tickers/ids que a Home já usa")
    void shouldParsePricesWithHomeIds() throws Exception {
        List<Indicator> indicators = CoinGeckoSource.parse(mapper.readTree(PRICES));

        assertEquals(4, indicators.size());
        Indicator btc = indicators.get(0);
        assertEquals("crypto_BTC", btc.getId());
        assertEquals("crypto", btc.getType());
        assertEquals("BTC", btc.getCode());
        assertEquals("Bitcoin", btc.getName());
        assertEquals(new BigDecimal("409129"), btc.getBuy());
        assertEquals(new BigDecimal("0.18"), btc.getVariation());
        assertEquals(CoinGeckoSource.SOURCE, btc.getSource());
        assertEquals(Instant.ofEpochSecond(1788703290L), btc.getAsOf());
        assertEquals("crypto_LTC", indicators.get(3).getId());
    }

    @Test
    @DisplayName("Série horária vira um ponto por dia UTC com a máxima do dia, do mais recente ao mais antigo")
    void shouldCollapseHourlyPricesIntoDailyHighs() throws Exception {
        // 2026-08-30 (dois pontos) e 2026-08-31 (um ponto), em milissegundos
        String chart = """
                {"prices":[[1788098400000, 409620.334],[1788102000000, 411999.284],[1788184800000, 403224.83]]}
                """;
        List<HistoricalDataPoint> points = CoinGeckoSource.parseHistory(mapper.readTree(chart));

        assertEquals(2, points.size());
        assertEquals(new BigDecimal("403224.83"), points.get(0).getHigh(), "dia mais recente primeiro");
        assertEquals("1788184800", points.get(0).getTimestamp(), "época em segundos, como a AwesomeAPI");
        assertEquals(new BigDecimal("411999.28"), points.get(1).getHigh(), "máxima do dia, não o último preço");
    }

    @Test
    @DisplayName("Sabe quais códigos são cripto — é o que decide a fonte alternativa do histórico")
    void shouldKnowSupportedCodes() {
        assertTrue(CoinGeckoSource.supports("BTC"));
        assertTrue(CoinGeckoSource.supports("eth"));
        assertFalse(CoinGeckoSource.supports("USD"));
        assertFalse(CoinGeckoSource.supports(null));
    }

    @Test
    @DisplayName("Pede preços em BRL com variação 24h e data, e o gráfico pelo id da moeda")
    void shouldRequestBrlPricesAndChart() {
        List<String> urls = new ArrayList<>();
        CoinGeckoSource source = new CoinGeckoSource(
                StubWebClient.respondingWith(urls, request -> StubWebClient.json(
                        request.url().getPath().contains("market_chart") ? "{\"prices\":[]}" : PRICES)),
                "https://example.test/coingecko");

        StepVerifier.create(source.fetch()).expectNextCount(1).verifyComplete();
        StepVerifier.create(source.history("btc", 7)).expectNextCount(1).verifyComplete();
        StepVerifier.create(source.history("USD", 7))
                .assertNext(points -> assertTrue(points.isEmpty()))
                .verifyComplete();

        assertEquals(2, urls.size(), "moeda que não é cripto não gera requisição");
        assertEquals("https://example.test/coingecko/simple/price?ids=bitcoin,ethereum,ripple,litecoin,dogecoin"
                + "&vs_currencies=brl&include_24hr_change=true&include_last_updated_at=true", urls.get(0));
        assertEquals("https://example.test/coingecko/coins/bitcoin/market_chart?vs_currency=brl&days=7", urls.get(1));
    }
}
