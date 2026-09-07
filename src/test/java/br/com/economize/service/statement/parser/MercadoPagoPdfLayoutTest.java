package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O extrato do Mercado Pago em PDF.
 *
 * <p>O caso que dá nome a esta suíte é a COSTURA: no layout do emissor, a
 * descrição quebra em três linhas em volta da linha do lançamento, e entre dois
 * lançamentos pode sobrar uma linha só — que tanto pode ser o fim da descrição
 * de cima quanto o começo da de baixo. Ler errado troca o estabelecimento de
 * dono, que é justamente o dado de que a categorização vive.
 *
 * <p>O texto abaixo é a transcrição fiel do que o PDFBox extrai de um extrato
 * real (nomes trocados), incluindo o cabeçalho que se repete a cada página.
 */
@DisplayName("Extrato do Mercado Pago (PDF)")
class MercadoPagoPdfLayoutTest {

    private static final String TEXTO = """
            EXTRATO DE CONTA
            Fulana de Tal
            CPF/CNPJ: 00000000000 Agencia: 1 Conta: 76986963927
            Periodo: De 01-08-2026 al 31-08-2026
            Entradas: R$ 2.802,23
            Saldo inicial: R$ 1.100,47 Saldo final: R$ 1.040,25
            Saidas: R$ -2.862,45
            DETALHE DOS MOVIMENTOS
            Data Descricao ID da operacao Valor Saldo
            Pagamento com QR Pix
            01-08-2026 SUPERMERCADOS 170691932753 R$ -194,99 R$ 905,48
            SEROPEDICA LTDA
            03-08-2026 Rendimentos 1747698576937 R$ 0,46 R$ 905,94
            06-08-2026 Rendimentos 1747926116691 R$ 0,38 R$ 907,10
            Pix recebido ERIC ANDRADE
            06-08-2026 171555930695 R$ 350,00 R$ 1.257,10
            SERVICOS INDUSTRIAIS LTDA
            Pix enviado Joao Cormino
            07-08-2026 172604220466 R$ -650,00 R$ 607,48
            Manso
            Pix recebido FULANA DOS
            07-08-2026 172653021776 R$ 729,92 R$ 1.337,40
            SANTOS
            07-08-2026 Dinheiro reservado Futuro 171748706757 R$ -729,92 R$ 607,48
            1/4
            Data Descricao ID da operacao Valor Saldo
            Pagamento com QR Pix VILA
            13-08-2026 SONIA COMERCIO E 172701915071 R$ -429,00 R$ 566,95
            REVENDA DE GAS LTDA
            13-08-2026 Pix enviado 172810097131 R$ -140,77 R$ 426,18
            13-08-2026 Pagamento Cartao de credito 172810765119 R$ -9,07 R$ 417,11
            Mercado Pago Instituicao de Pagamento Ltda. CNPJ n. 10.573.521/0001-91.
            """;

    private final List<ParsedTransaction> lidas = MercadoPagoPdfLayout.parse(TEXTO);

    @Test
    @DisplayName("reconhece o documento pelas duas marcas, e não por chute")
    void recognisesTheDocument() {
        assertThat(MercadoPagoPdfLayout.reconhece(TEXTO)).isTrue();
        // Extrato de outro banco não pode cair neste leitor
        assertThat(MercadoPagoPdfLayout.reconhece("EXTRATO CONTA CORRENTE\nBanco Inter")).isFalse();
        // O nome do emissor sozinho não basta: um Pix "para Mercado Pago" no
        // extrato de outro banco traria a palavra sem o layout
        assertThat(MercadoPagoPdfLayout.reconhece("Pix enviado Mercado Pago")).isFalse();
    }

    @Test
    @DisplayName("lê todos os lançamentos, e nenhuma linha de cabeçalho")
    void readsEveryRowAndNoHeader() {
        assertThat(lidas).hasSize(10);
        assertThat(lidas).allSatisfy(tx -> assertThat(tx.getDescription()).isNotBlank());
    }

    @Test
    @DisplayName("costura a descrição quebrada nas três linhas, na ordem da leitura")
    void stitchesTheWrappedDescription() {
        assertThat(lidas.get(0).getDescription())
                .isEqualTo("Pagamento com QR Pix SUPERMERCADOS SEROPEDICA LTDA");
        assertThat(lidas.get(7).getDescription())
                .isEqualTo("Pagamento com QR Pix VILA SONIA COMERCIO E REVENDA DE GAS LTDA");
    }

    @Test
    @DisplayName("a sobra entre dois lançamentos vai para o DONO certo")
    void assignsTheStrayLineToTheRightRow() {
        // Este é o teste que justifica o vocabulário de inícios de descrição.
        // Depois do "Pagamento com QR Pix …" vem uma sobra ("SEROPEDICA LTDA") e
        // logo abaixo um "Rendimentos": a sobra é o FIM do de cima, e o de baixo
        // não pode herdá-la
        assertThat(lidas.get(1).getDescription()).isEqualTo("Rendimentos");
        // E o inverso: depois de um "Rendimentos" completo vem uma sobra
        // ("Pix recebido ERIC ANDRADE") que é o COMEÇO do de baixo
        assertThat(lidas.get(2).getDescription()).isEqualTo("Rendimentos");
        assertThat(lidas.get(3).getDescription())
                .isEqualTo("Pix recebido ERIC ANDRADE SERVICOS INDUSTRIAIS LTDA");
    }

    @Test
    @DisplayName("duas sobras seguidas se dividem entre o de cima e o de baixo")
    void splitsATwoLineStrayBlock() {
        // "Manso" fecha o Pix enviado; "Pix recebido FULANA DOS" abre o seguinte
        assertThat(lidas.get(4).getDescription()).isEqualTo("Pix enviado Joao Cormino Manso");
        assertThat(lidas.get(5).getDescription()).isEqualTo("Pix recebido FULANA DOS SANTOS");
    }

    @Test
    @DisplayName("o sinal do valor decide entrada e saída")
    void signDecidesTheDirection() {
        assertThat(lidas.get(0).getType()).isEqualTo("DEBIT");
        assertThat(lidas.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-194.99"));
        assertThat(lidas.get(1).getType()).isEqualTo("CREDIT");
        assertThat(lidas.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("0.46"));
    }

    @Test
    @DisplayName("o id da operação vira a chave — reenviar o mesmo mês não duplica")
    void usesTheOperationIdAsTheKey() {
        assertThat(lidas.get(0).getExternalId()).isEqualTo("MP-170691932753");
        assertThat(lidas).extracting(ParsedTransaction::getExternalId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a data vem no formato do emissor, dd-MM-yyyy")
    void parsesTheIssuerDateFormat() {
        assertThat(lidas.get(0).getDate().toLocalDate()).hasToString("2026-08-01");
        assertThat(lidas.get(9).getDate().toLocalDate()).hasToString("2026-08-13");
    }

    @Test
    @DisplayName("o cabeçalho repetido a cada página e o rodapé legal não viram lançamento")
    void ignoresRepeatedHeadersAndLegalFooter() {
        assertThat(lidas).extracting(ParsedTransaction::getDescription)
                .noneMatch(d -> d.contains("ID da operacao"))
                .noneMatch(d -> d.contains("CNPJ"))
                .noneMatch(d -> d.contains("Instituicao de Pagamento"));
    }

    @Test
    @DisplayName("mês sem movimento devolve lista vazia, e não lixo")
    void emptyMonthYieldsNothing() {
        // Abril de 2026 na conta real: R$ 0,00 de entrada e de saída
        String vazio = """
                EXTRATO DE CONTA
                Periodo: De 01-04-2026 al 30-04-2026
                DETALHE DOS MOVIMENTOS
                Data Descricao ID da operacao Valor Saldo
                Mercado Pago Instituicao de Pagamento Ltda.
                """;

        assertThat(MercadoPagoPdfLayout.parse(vazio)).isEmpty();
    }

    @Test
    @DisplayName("Mês parado: o extrato diz que não houve movimento, e isso não é falha de leitura")
    void reconheceMesSemMovimento() {
        // Texto real do extrato de abril/2026 (conta que ficou o mês inteiro
        // zerada). Sem esta distinção o upload respondia "não foi possível
        // extrair transações confiáveis — exporte em OFX", que manda procurar
        // defeito num arquivo perfeito.
        String abril = """
                EXTRATO DE CONTA
                Fulana de Tal
                CPF/CNPJ: 00000000000  1  76986963927Agencia: Conta:
                 De 01-04-2026 al 30-04-2026Periodo:
                Saldo inicial: R$ 0,00
                Entradas: R$ 0,00
                Saidas: R$ 0,00
                DETALHE DOS MOVIMENTOS
                Data Descricao ID da operacao Valor Saldo
                Saldo final: R$ 0,00
                Mercado Pago Instituicao de Pagamento Ltda.
                """;

        assertThat(MercadoPagoPdfLayout.reconhece(abril)).isTrue();
        assertThat(MercadoPagoPdfLayout.parse(abril)).isEmpty();
        assertThat(MercadoPagoPdfLayout.semMovimento(abril)).isTrue();
    }

    @Test
    @DisplayName("Extrato COM movimento nunca é confundido com mês parado")
    void mesComMovimentoNaoEhZerado() {
        // A guarda importa nos dois sentidos: um extrato cheio marcado como
        // "sem movimento" faria o upload recusar dado bom
        assertThat(MercadoPagoPdfLayout.semMovimento(TEXTO)).isFalse();
    }
}
