package br.com.economize.service.investment;

import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.InvestmentPosition.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O mapeamento do payload do Pluggy para o vocabulário do produto, sobre
 * respostas realistas: um CDB, um Tesouro Selic, uma ETF e o que costuma vir
 * quebrado.
 */
@DisplayName("PluggyInvestmentMapper — do payload do Pluggy à posição")
class PluggyInvestmentMapperTest {

    /** Um CDB como o Pluggy devolve: números como Double, datas com hora. */
    static Map<String, Object> cdb() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "inv-cdb-1");
        raw.put("itemId", "item-1");
        raw.put("type", "FIXED_INCOME");
        raw.put("subtype", "CDB");
        raw.put("name", "CDB Banco Inter 110% CDI");
        raw.put("code", "CDB1234");
        raw.put("balance", 1123.45);
        raw.put("amount", 1100.00);
        raw.put("amountOriginal", 1000.00);
        raw.put("amountProfit", 123.45);
        raw.put("currencyCode", "BRL");
        raw.put("date", "2026-09-01T03:00:00.000Z");
        raw.put("dueDate", "2028-03-15T03:00:00.000Z");
        raw.put("issuer", "Banco Inter S.A.");
        raw.put("rate", 110);
        raw.put("rateType", "CDI");
        raw.put("status", "ACTIVE");
        return raw;
    }

    /** Tesouro chega como FIXED_INCOME/TREASURY e o indexador só está no NOME. */
    static Map<String, Object> tesouroSelic() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "inv-tes-1");
        raw.put("type", "FIXED_INCOME");
        raw.put("subtype", "TREASURY");
        raw.put("name", "Tesouro Selic 2029");
        raw.put("balance", 5230.10);
        raw.put("amountOriginal", 5000);
        raw.put("quantity", 0.35);
        raw.put("value", 14943.14);
        raw.put("date", "2026-09-02");
        raw.put("dueDate", "2029-03-01");
        raw.put("annualRate", 14.25);
        return raw;
    }

    /** Uma ETF em dólar: quantidade e cotação, sem aplicado nem taxa. */
    static Map<String, Object> etf() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", "inv-etf-1");
        raw.put("type", "ETF");
        raw.put("name", "Vanguard Total World Stock ETF");
        raw.put("code", "vt");
        raw.put("quantity", 12);
        raw.put("value", 118.42);
        raw.put("currencyCode", "USD");
        raw.put("date", "2026-09-03");
        return raw;
    }

    @Test
    @DisplayName("CDB: renda fixa indexada ao CDI, taxa como texto, valores e datas corretos")
    void mapeiaCdb() {
        PluggyInvestmentMapper.Mapped m = PluggyInvestmentMapper.map(cdb());

        assertThat(m.providerId()).isEqualTo("inv-cdb-1");
        assertThat(m.type()).isEqualTo(Type.FIXED_INCOME);
        assertThat(m.subtype()).isEqualTo("CDB");
        assertThat(m.indexer()).isEqualTo(Indexer.CDI);
        assertThat(m.rate()).isEqualTo("110% CDI");
        assertThat(m.issuer()).isEqualTo("Banco Inter S.A.");
        // o valor atual é o balance (bruto), não o amount (líquido): é o que o
        // extrato da instituição mostra como saldo da aplicação
        assertThat(m.currentValue()).isEqualByComparingTo("1123.45");
        assertThat(m.investedAmount()).isEqualByComparingTo("1000.00");
        assertThat(m.currency()).isEqualTo("BRL");
        assertThat(m.positionDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(m.maturityDate()).isEqualTo(LocalDate.of(2028, 3, 15));
        assertThat(m.code()).isEqualTo("CDB1234");
    }

    @Test
    @DisplayName("Tesouro Selic: vira TREASURY com indexador lido do nome e subtipo próprio")
    void mapeiaTesouroSelic() {
        PluggyInvestmentMapper.Mapped m = PluggyInvestmentMapper.map(tesouroSelic());

        assertThat(m.type()).isEqualTo(Type.TREASURY);
        assertThat(m.subtype()).isEqualTo("TESOURO_SELIC");
        assertThat(m.indexer()).isEqualTo(Indexer.SELIC);
        assertThat(m.quantity()).isEqualByComparingTo("0.35");
        assertThat(m.unitPrice()).isEqualByComparingTo("14943.14");
        // sem rate/rateType, o rendimento anual vira a taxa de apresentação
        assertThat(m.rate()).isEqualTo("14,25% a.a.");
        assertThat(m.maturityDate()).isEqualTo(LocalDate.of(2029, 3, 1));
    }

    @Test
    @DisplayName("Tesouro IPCA+ e Prefixado: o indexador acompanha o nome do título")
    void mapeiaOutrosTesouros() {
        Map<String, Object> ipca = tesouroSelic();
        ipca.put("id", "inv-tes-2");
        ipca.put("name", "Tesouro IPCA+ 2035");
        ipca.put("fixedAnnualRate", 6.2);
        ipca.remove("annualRate");
        PluggyInvestmentMapper.Mapped mIpca = PluggyInvestmentMapper.map(ipca);
        assertThat(mIpca.indexer()).isEqualTo(Indexer.IPCA);
        assertThat(mIpca.subtype()).isEqualTo("TESOURO_IPCA");
        assertThat(mIpca.rate()).isEqualTo("IPCA + 6,20%");

        Map<String, Object> pre = tesouroSelic();
        pre.put("id", "inv-tes-3");
        pre.put("name", "Tesouro Prefixado 2027");
        pre.put("fixedAnnualRate", 12);
        pre.remove("annualRate");
        PluggyInvestmentMapper.Mapped mPre = PluggyInvestmentMapper.map(pre);
        assertThat(mPre.indexer()).isEqualTo(Indexer.PREFIXADO);
        assertThat(mPre.subtype()).isEqualTo("TESOURO_PREFIXADO");
        assertThat(mPre.rate()).isEqualTo("12% a.a.");
    }

    @Test
    @DisplayName("ETF em dólar: código maiúsculo, indexador USD e valor atual = quantidade × cotação")
    void mapeiaEtf() {
        PluggyInvestmentMapper.Mapped m = PluggyInvestmentMapper.map(etf());

        assertThat(m.type()).isEqualTo(Type.ETF);
        assertThat(m.code()).isEqualTo("VT");
        assertThat(m.currency()).isEqualTo("USD");
        assertThat(m.indexer()).isEqualTo(Indexer.USD);
        assertThat(m.quantity()).isEqualByComparingTo("12");
        assertThat(m.unitPrice()).isEqualByComparingTo("118.42");
        // sem balance nem amount, o produto quantidade × cotação é o valor
        // atual — melhor do que "não sei" quando os dois fatores estão aí
        assertThat(m.currentValue()).isEqualByComparingTo("1421.04");
        assertThat(m.investedAmount()).isNull();
        assertThat(m.rate()).isNull();
        assertThat(m.maturityDate()).isNull();
    }

    @Test
    @DisplayName("Ação, FII, fundo, previdência e COE caem no tipo do produto")
    void mapeiaTiposRestantes() {
        assertThat(PluggyInvestmentMapper.map(Map.of("id", "a", "type", "EQUITY", "subtype", "STOCK", "name", "PETR4")).type())
                .isEqualTo(Type.EQUITY);
        PluggyInvestmentMapper.Mapped fii = PluggyInvestmentMapper.map(
                Map.of("id", "b", "type", "EQUITY", "subtype", "REAL_ESTATE_FUND", "name", "HGLG11"));
        assertThat(fii.type()).isEqualTo(Type.FUND);
        assertThat(fii.subtype()).isEqualTo("FII");
        assertThat(PluggyInvestmentMapper.map(Map.of("id", "c", "type", "EQUITY", "subtype", "ETF", "name", "IVVB11")).type())
                .isEqualTo(Type.ETF);
        assertThat(PluggyInvestmentMapper.map(Map.of("id", "d", "type", "MUTUAL_FUND", "subtype", "MULTIMARKET_FUND", "name", "Fundo X")).type())
                .isEqualTo(Type.FUND);
        // no Pluggy, SECURITY é previdência (PGBL/VGBL), não título
        assertThat(PluggyInvestmentMapper.map(Map.of("id", "e", "type", "SECURITY", "subtype", "RETIREMENT", "name", "VGBL")).type())
                .isEqualTo(Type.PENSION);
        PluggyInvestmentMapper.Mapped coe = PluggyInvestmentMapper.map(Map.of("id", "f", "type", "COE", "name", "COE S&P"));
        assertThat(coe.type()).isEqualTo(Type.OTHER);
        assertThat(coe.subtype()).isEqualTo("COE");
        // ação em real não tem indexador — e a resposta diz isso, não "CDI"
        assertThat(PluggyInvestmentMapper.map(Map.of("id", "a", "type", "EQUITY", "name", "PETR4")).indexer())
                .isEqualTo(Indexer.NONE);
    }

    @Test
    @DisplayName("Sem id não há posição; campos torto viram nulo, nunca derrubam o mapeamento")
    void tolerantePayloadQuebrado() {
        assertThat(PluggyInvestmentMapper.map(Map.of("type", "FIXED_INCOME", "name", "sem id"))).isNull();
        assertThat(PluggyInvestmentMapper.map(null)).isNull();

        Map<String, Object> torto = new HashMap<>();
        torto.put("id", "x");
        torto.put("type", "ALGO_NOVO");
        torto.put("balance", "não é número");
        torto.put("date", "ontem");
        torto.put("currencyCode", "reais");
        torto.put("quantity", Double.NaN);
        PluggyInvestmentMapper.Mapped m = PluggyInvestmentMapper.map(torto);

        assertThat(m.type()).isEqualTo(Type.OTHER);
        assertThat(m.name()).isEqualTo("Investimento");
        assertThat(m.currentValue()).isNull();
        assertThat(m.quantity()).isNull();
        assertThat(m.positionDate()).isNull();
        assertThat(m.currency()).isEqualTo("BRL");
    }

    @Test
    @DisplayName("Taxa que já vem como texto no rate é usada como está; nome longo é truncado ao limite da coluna")
    void taxaTextualENomeLongo() {
        Map<String, Object> raw = cdb();
        raw.put("rate", "100% do CDI + 1%");
        raw.remove("rateType");
        raw.put("name", "X".repeat(300));
        PluggyInvestmentMapper.Mapped m = PluggyInvestmentMapper.map(raw);

        assertThat(m.rate()).isEqualTo("100% do CDI + 1%");
        assertThat(m.indexer()).isEqualTo(Indexer.CDI);
        assertThat(m.name()).hasSize(PluggyInvestmentMapper.NAME_MAX);
    }
}
