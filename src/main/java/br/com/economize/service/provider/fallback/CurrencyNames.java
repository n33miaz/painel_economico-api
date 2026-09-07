package br.com.economize.service.provider.fallback;

import br.com.economize.dto.Indicator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Nomes e identidade das moedas no mesmo formato que a AwesomeAPI emite
 * ("Dólar Americano/Real Brasileiro", id currency_USD). As fontes alternativas
 * devolvem só o código; sem esta tabela o item da Home mudaria de nome cada vez
 * que a fonte mudasse, e o app, que casa favoritos por id, perderia o vínculo.
 */
final class CurrencyNames {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("USD", "Dólar Americano/Real Brasileiro"),
            Map.entry("EUR", "Euro/Real Brasileiro"),
            Map.entry("GBP", "Libra Esterlina/Real Brasileiro"),
            Map.entry("JPY", "Iene Japonês/Real Brasileiro"),
            Map.entry("CHF", "Franco Suíço/Real Brasileiro"),
            Map.entry("CAD", "Dólar Canadense/Real Brasileiro"),
            Map.entry("AUD", "Dólar Australiano/Real Brasileiro"),
            Map.entry("CNY", "Yuan Chinês/Real Brasileiro"),
            Map.entry("ARS", "Peso Argentino/Real Brasileiro"),
            Map.entry("BTC", "Bitcoin"),
            Map.entry("ETH", "Ethereum"),
            Map.entry("XRP", "XRP"),
            Map.entry("LTC", "Litecoin"),
            Map.entry("DOGE", "Dogecoin"));

    private CurrencyNames() {
    }

    static String nameOf(String code) {
        return NAMES.getOrDefault(code, code + "/Real Brasileiro");
    }

    /** Um item de moeda fiat no shape do /all, pronto para a Home. */
    static Indicator fiat(String code, BigDecimal buy, BigDecimal sell, BigDecimal variation,
            String source, Instant asOf) {
        Indicator indicator = new Indicator();
        indicator.setId("currency_" + code);
        indicator.setType("currency");
        indicator.setCode(code);
        indicator.setCodeIn("BRL");
        indicator.setName(nameOf(code));
        indicator.setBuy(buy);
        indicator.setSell(sell);
        indicator.setVariation(variation);
        indicator.setSource(source);
        indicator.setAsOf(asOf);
        return indicator;
    }

    /** Um item de cripto no shape do /all. */
    static Indicator crypto(String code, BigDecimal price, BigDecimal variation, String source, Instant asOf) {
        Indicator indicator = new Indicator();
        indicator.setId("crypto_" + code);
        indicator.setType("crypto");
        indicator.setCode(code);
        indicator.setCodeIn("BRL");
        indicator.setName(nameOf(code));
        indicator.setBuy(price);
        indicator.setSell(price);
        indicator.setVariation(variation);
        indicator.setSource(source);
        indicator.setAsOf(asOf);
        return indicator;
    }
}
