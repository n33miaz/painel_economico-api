package br.com.economize.dto.indicator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Um título do Tesouro Direto com as taxas e preços do dia.
 *
 * @param indexer        SELIC, IPCA, PREFIXADO ou OTHER (IGP-M e afins);
 *                       Renda+ e Educa+ são IPCA
 * @param annualRateBuy  taxa anual para quem INVESTE hoje
 * @param annualRateSell taxa anual usada no RESGATE antecipado
 * @param unitPriceBuy   preço unitário de compra
 * @param unitPriceSell  preço unitário de venda (resgate)
 * @param minInvestment  aplicação mínima; nula quando a fonte não a informa
 *                       (o CSV do Tesouro Transparente não traz)
 * @param asOf           momento de referência dos preços
 * @param stale          true quando veio do snapshot porque as fontes falharam
 */
public record TreasuryBond(
        String name,
        String indexer,
        LocalDate maturity,
        BigDecimal annualRateBuy,
        BigDecimal annualRateSell,
        BigDecimal unitPriceBuy,
        BigDecimal unitPriceSell,
        BigDecimal minInvestment,
        Instant asOf,
        String source,
        boolean stale) {

    public static final String SELIC = "SELIC";
    public static final String IPCA = "IPCA";
    public static final String PREFIXADO = "PREFIXADO";
    public static final String OTHER = "OTHER";

    public TreasuryBond asStale() {
        return new TreasuryBond(name, indexer, maturity, annualRateBuy, annualRateSell, unitPriceBuy,
                unitPriceSell, minInvestment, asOf, source, true);
    }
}
