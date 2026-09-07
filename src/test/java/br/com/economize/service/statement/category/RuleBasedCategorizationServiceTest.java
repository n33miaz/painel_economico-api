package br.com.economize.service.statement.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCategorizationServiceTest {

    private final RuleBasedCategorizationService service = new RuleBasedCategorizationService();

    private String keyOf(String description) {
        return service.match(description).map(RuleBasedCategorizationService.Hit::systemKey).orElse(null);
    }

    @Test
    void mapsFoodKeywords() {
        assertThat(service.categorize("IFOOD ORDER 9821", "DEBIT")).isEqualTo(TransactionCategory.FOOD);
        assertThat(service.categorize("Supermercado Extra", "DEBIT")).isEqualTo(TransactionCategory.FOOD);
    }

    @Test
    void reconheceOPrefixoDaMaquininhaDoIfood() {
        // "IFD*" e como o iFood aparece no extrato de cartao e de vale-refeicao.
        // Medido no extrato real do Flash: sem esta regra, QUATRO grupos de
        // pedidos ficavam sem sugestao nenhuma, porque a palavra "ifood" so
        // aparece em alguns deles
        assertThat(keyOf("IFD*FOOD ACAI LTDA SEROPEDICA BRA")).isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("IFD*REI DO ARTESANAL H SEROPEDICA BRA")).isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("IFD *FOOD ACAI LTDA")).isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("99Food *Esfihas Ariston p Sao Paulo BRA")).isEqualTo("FOOD_DELIVERY");
    }

    @Test
    void reconheceAFolhaDePagamentoDoExtratoReal() {
        // Medido no extrato real do dono: o MAIOR crédito recorrente dele é
        // "LIQUIDO DE VENCIMENTO CNPJ ..." e não casava com nada. Caía no
        // fallback, parava na raiz "Receitas" e ficava fora de "Salário" — que
        // é justamente de onde saem o valor da hora e o cálculo de renda.
        assertThat(keyOf("LIQUIDO DE VENCIMENTO   CNPJ 006210366000171"))
                .isEqualTo("INCOME_SALARY");
        assertThat(keyOf("Salário recebido - portabilidade")).isEqualTo("INCOME_SALARY");
        assertThat(keyOf("PRO LABORE SETEMBRO")).isEqualTo("INCOME_SALARY");
    }

    @Test
    void vencimentoDaFaturaNaoEhSalario() {
        // "vencimento" solto casaria com o vencimento de uma fatura, e o
        // pagamento do cartão viraria salário recebido. É por isso que a regra
        // usa a frase inteira, e este teste é o que impede alguém de encurtá-la.
        assertThat(keyOf("Pagamento fatura cartao Inter - vencimento 14/09"))
                .isNotEqualTo("INCOME_SALARY");
        assertThat(keyOf("Boleto vencimento 10/10")).isNotEqualTo("INCOME_SALARY");
    }

    @Test
    void oPrefixoCurtoNaoCasaDentroDeOutraPalavra() {
        // "ifd" tem tres letras: solto, casaria dentro de qualquer nome. Por
        // isso a regra e de palavra INTEIRA
        assertThat(keyOf("SWIFDATA SOLUCOES LTDA")).isNotEqualTo("FOOD_DELIVERY");
    }

    @Test
    void mapsTransport() {
        assertThat(service.categorize("UBER TRIP", "DEBIT")).isEqualTo(TransactionCategory.TRANSPORT);
        assertThat(service.categorize("POSTO IPIRANGA", "DEBIT")).isEqualTo(TransactionCategory.TRANSPORT);
    }

    @Test
    void defaultsCreditToIncome() {
        assertThat(service.categorize("Algo desconhecido", "CREDIT")).isEqualTo(TransactionCategory.INCOME);
    }

    @Test
    void fallsBackToOther() {
        assertThat(service.categorize("Compra qualquer obscura", "DEBIT")).isEqualTo(TransactionCategory.OTHER);
    }

    @Test
    void resolvesToTheSubcategoryNotJustTheParent() {
        assertThat(keyOf("IFOOD *PEDIDO 8812")).isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("AUTOPASS RECARGA")).isEqualTo("TRANSPORT_PUBLIC");
        assertThat(keyOf("APLICACAO CDB INTER")).isEqualTo("INVESTMENT_FIXED");
        assertThat(keyOf("SABESP AGOSTO")).isEqualTo("UTILITIES_WATER");
        assertThat(keyOf("Pix enviado Claudia")).isEqualTo("TRANSFER_PIX");
    }

    @Test
    void lanchoneteIsARestaurantEvenWhenGluedOrPaidByPix() {
        // "lanche" não está dentro de "lanchonete" (lanch-o-nete): a compra ficava
        // sem categoria e o PIX para a lanchonete caía em Transferências
        assertThat(keyOf("Compra no debito: \"No estabelecimento SolLanchonete VILAREAL BRA\""))
                .isEqualTo("FOOD_RESTAURANT");
        assertThat(keyOf("Pix enviado: \"Cp :123-LANCHONETE ESTRELA DO SUL\"")).isEqualTo("FOOD_RESTAURANT");
        assertThat(keyOf("LANCHES DO ZE")).isEqualTo("FOOD_RESTAURANT");
    }

    @Test
    void carriesTheParentKeyAsFallback() {
        RuleBasedCategorizationService.Hit hit = service.match("NETFLIX.COM").orElseThrow();
        assertThat(hit.systemKey()).isEqualTo("LEISURE_STREAMING");
        assertThat(hit.parentKey()).isEqualTo("LEISURE");
    }

    @Test
    void longestKeywordWinsOverDeclarationOrder() {
        // "mercado" (Alimentação) vinha antes e sequestrava a compra no Mercado Livre
        assertThat(keyOf("MERCADO LIVRE*COMPRA")).isEqualTo("SHOPPING_ONLINE");
        // e "uber" sequestrava o pedido do Uber Eats
        assertThat(keyOf("UBER EATS SP")).isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("UBER *TRIP SP")).isEqualTo("TRANSPORT_RIDE");
    }

    @Test
    void methodBeatsTheGenericWordItLivesInside() {
        // achado no extrato real do Nubank: "transferencia" (13 letras) vencia
        // "pix" (3) e mandava todo Pix do Nubank para TED e DOC
        assertThat(keyOf("Transferência enviada pelo Pix - NEEMIAS C M - BANCO INTER"))
                .isEqualTo("TRANSFER_PIX");
        assertThat(keyOf("Transferência recebida pelo Pix - MARIA S"))
                .isEqualTo("TRANSFER_PIX");
        // e um TED de verdade continua sendo TED
        assertThat(keyOf("TED RECEBIDA DE BANCO X")).isEqualTo("TRANSFER_TED");
    }

    @Test
    void theEstablishmentBeatsTheMeansOfPayment() {
        // achado no extrato real do Inter: "pix" estava acima de TODO o vocabulário
        // de estabelecimento, então 44% do extrato (736 Pix) virava "Pix" e escondia
        // o que a pessoa de fato comprou
        assertThat(keyOf("Pix enviado: \"Cp :21018182-IFOOD.COM AGENCIA DE RESTAURANTES ONLINE\""))
                .isEqualTo("FOOD_DELIVERY");
        assertThat(keyOf("Pix enviado: \"MERCADINHO DO ZE\"")).isEqualTo("FOOD_GROCERIES");
        assertThat(keyOf("Pix enviado: \"POSTO DEZ COMBUSTIVEIS\"")).isEqualTo("TRANSPORT_FUEL");
        // e o Pix para uma pessoa, sem nada mais específico, continua sendo Pix
        assertThat(keyOf("Pix enviado: \"Cp :00011122-MARIA DA SILVA\"")).isEqualTo("TRANSFER_PIX");
    }

    @Test
    void pixKeywordDoesNotLeakIntoWordsThatMerelyStartWithIt() {
        assertThat(keyOf("COMPRA PIXEL STORE ONLINE")).isNotEqualTo("TRANSFER_PIX");
    }

    @Test
    void shortBrandsOnlyMatchAsWholeWords() {
        // achados com extrato real: "amil" mora dentro de "CAMILA" e mandava um
        // Pix recebido de uma pessoa para Plano de saúde
        assertThat(keyOf("Pix recebido: \"Cp :31872495-CAMILA MARIANA SERAFIM PAZINI\""))
                .isEqualTo("TRANSFER_PIX");
        assertThat(keyOf("PAGAMENTO GOOGLE PAYMENT LIMITED")).isNotEqualTo("TRANSFER_TED");
        assertThat(keyOf("COMPRA BIOFARMA MANIPULACAO")).isNotEqualTo("FEES_IOF");
        // e o termo continua valendo quando é palavra de verdade
        assertThat(keyOf("DOC RECEBIDO DE BANCO X")).isEqualTo("TRANSFER_TED");
        assertThat(keyOf("MENSALIDADE AMIL SAUDE")).isEqualTo("HEALTH_INSURANCE");
    }

    /**
     * A palavra "seguro" desempata contra o vocabulário de saúde, e é o desempate
     * por COMPRIMENTO que decide — não a ordem de declaração. "SEGUROS UNIMED" e
     * "UNIMED SEGUROS SAUDE" são a mesma empresa real de PLANO DE SAÚDE, e
     * qualquer termo do vocabulário de seguro com mais letras do que "unimed"
     * (6) rouba as duas para Seguro de vida.
     */
    @Test
    void insuranceVocabularyMustNotStealTheHealthPlan() {
        assertThat(keyOf("SEGUROS UNIMED")).isEqualTo("HEALTH_INSURANCE");
        assertThat(keyOf("UNIMED SEGUROS SAUDE")).isEqualTo("HEALTH_INSURANCE");
        assertThat(keyOf("PAGTO MENSAL UNIMED")).isEqualTo("HEALTH_INSURANCE");
    }

    @Test
    void wholeWordSeguroKillsThePagseguroFalsePositive() {
        // "seguro" como PEDAÇO vive dentro de "PAGSEGURO", uma das maiores
        // maquininhas do país: toda compra passada nela virava Seguro de vida, e o
        // usuário aprovando na revisão ensinava a regra errada
        assertThat(keyOf("PAGSEGURO *MERCANTE")).isNotEqualTo("INSURANCE_LIFE");
        assertThat(keyOf("Pagseguro *Elc")).isNotEqualTo("INSURANCE_LIFE");
        // e o seguro de verdade, como palavra inteira, continua casando
        assertThat(keyOf("SEGURO DE VIDA PRESTAMISTA")).isEqualTo("INSURANCE_LIFE");
        assertThat(keyOf("DEBITO SEGURO PROTECAO FINANCEIRA")).isEqualTo("INSURANCE_LIFE");
    }

    /**
     * Um system_key com typo no vocabulário não quebra nada visivelmente — só manda
     * a transação calada para a fila de revisão. Este teste amarra o vocabulário
     * ao catálogo semeado.
     */
    @Test
    void everyTargetKeyExistsInTheSeedMigrations() throws IOException {
        String seeds = read("/db/migration/V6__categories_rules_and_review.sql")
                + read("/db/migration/V8__investment_seed_category.sql")
                + read("/db/migration/V9__category_hierarchy.sql")
                + read("/db/migration/V10__insurance_category.sql");

        List<String> missing = service.allTargetKeys().stream()
                .distinct()
                .filter(key -> !seeds.contains("'" + key + "'"))
                .toList();

        assertThat(missing).as("system_keys do vocabulário que não existem no catálogo").isEmpty();
    }

    private String read(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("migration %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Razão social no extrato: o vocabulário só conhecia a marca")
    void reconheceRazaoSocialDoExtratoReal() {
        // Todas estas descrições são do extrato real da conta importada em
        // 07/09/2026, e todas caíam em "Transferências > Pix": o pagamento por
        // QR do Mercado Pago traz a razão social, e o vocabulário conhecia
        // "99app"/"99pop" mas não "99 TECNOLOGIA LTDA". É a mesma classe do IFD*.
        assertThat(keyOf("Pagamento com QR Pix 99 TECNOLOGIA LTDA"))
                .isEqualTo("TRANSPORT_RIDE");
        assertThat(keyOf("Pagamento com QR Pix PRODATA MOBILITY BRASIL"))
                .isEqualTo("TRANSPORT_PUBLIC");
        assertThat(keyOf("Pagamento com QR Pix BB TRANSPORTE E TURISMO"))
                .isEqualTo("TRANSPORT_PUBLIC");
        assertThat(keyOf("COMERCIO DE MATERIAIS PARA CONSTRUCAO JOLI LTDA"))
                .isEqualTo("HOUSING_GOODS");
        assertThat(keyOf("SERVICO NACIONAL DE APRENDIZAGEM COMERCIAL SENAC"))
                .isEqualTo("EDUCATION_COURSES");
    }

    @Test
    @DisplayName("O crédito do vale-refeição é BENEFÍCIO, e chega pelo nome da emissora")
    void creditoDeValeRefeicaoEhBeneficio() {
        // "Pix recebido Flash Tecnologia e Instituição de Pagamento Ltda" é o
        // vale-refeição caindo na conta. Como transferência, ele não entrava na
        // renda e ficava fora do valor da hora e do lembrete de queda do
        // benefício (ponto em aberto do registro 63.11).
        assertThat(keyOf("Pix recebido Flash Tecnologia e Instituicao de Pagamento Ltda"))
                .isEqualTo("INCOME_BENEFITS");
        assertThat(keyOf("Credito de vale refeicao")).isEqualTo("INCOME_BENEFITS");
        assertThat(keyOf("ALELO BENEFICIOS")).isEqualTo("INCOME_BENEFITS");
    }

    @Test
    @DisplayName("O estabelecimento continua vencendo o meio de pagamento")
    void estabelecimentoVenceOMeio() {
        // A guarda que protege as palavras novas: elas chegam DEPOIS da palavra
        // "Pix" na descrição, e o meio não pode voltar a sequestrar a linha
        assertThat(keyOf("Pagamento com QR Pix UBER DO BRASIL TECNOLOGIA"))
                .isEqualTo("TRANSPORT_RIDE");
        assertThat(keyOf("Pagamento com QR Pix SUPERMERCADOS SEROPEDICA LTDA"))
                .isEqualTo("FOOD_GROCERIES");
        // e um Pix para uma PESSOA continua sendo Pix: não há estabelecimento
        assertThat(keyOf("Pix enviado Marianna de Oliveira Avelar")).isEqualTo("TRANSFER_PIX");
    }
}
