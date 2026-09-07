package br.com.economize.dto.indicator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Cotação de um ETF ou ação estrangeira (ex.: VT, o Vanguard Total World).
 *
 * @param priceBrl      preço convertido pelo dólar do agregado da Home (ou do
 *                      snapshot dele); nulo quando não há dólar disponível ou a
 *                      cotação não é em USD
 * @param change        variação absoluta contra o fechamento anterior; nula
 *                      quando a fonte não a dá (Stooq)
 * @param changePercent idem, em %
 * @param date          pregão a que o preço se refere
 * @param asOf          momento da cotação segundo a fonte
 * @param stale         true quando veio do snapshot porque as fontes falharam
 */
public record ForeignQuote(
        String symbol,
        String market,
        BigDecimal price,
        String currency,
        BigDecimal priceBrl,
        BigDecimal change,
        BigDecimal changePercent,
        LocalDate date,
        String source,
        Instant asOf,
        boolean stale) {

    public ForeignQuote withPriceBrl(BigDecimal converted) {
        return new ForeignQuote(symbol, market, price, currency, converted, change, changePercent, date, source,
                asOf, stale);
    }

    public ForeignQuote asStale() {
        return new ForeignQuote(symbol, market, price, currency, priceBrl, change, changePercent, date, source,
                asOf, true);
    }
}
