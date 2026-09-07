package br.com.economize.dto.indicator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Um indicador macro que mexe no que a pessoa normalmente tem: CDI e Selic
 * (CDB/CDI, Tesouro Selic), IPCA (Tesouro IPCA+), PTAX (ETF no exterior),
 * poupança e IGP-M. É a base da personalização: o app escolhe quais mostrar
 * conforme o que o usuário investe.
 *
 * @param code          identificador estável (CDI, SELIC, IPCA_MES, IPCA_12M,
 *                      USD_PTAX, POUPANCA, IGPM)
 * @param unit          "% a.a.", "% a.m.", "%" ou "BRL"
 * @param referenceDate data a que o valor se refere (o mês do IPCA, o dia do CDI)
 * @param asOf          quando o número foi obtido da fonte
 * @param stale         true quando veio do snapshot porque a fonte falhou
 */
public record MacroIndicator(
        String code,
        String name,
        BigDecimal value,
        String unit,
        LocalDate referenceDate,
        String source,
        Instant asOf,
        boolean stale) {

    public MacroIndicator asStale() {
        return new MacroIndicator(code, name, value, unit, referenceDate, source, asOf, true);
    }
}
