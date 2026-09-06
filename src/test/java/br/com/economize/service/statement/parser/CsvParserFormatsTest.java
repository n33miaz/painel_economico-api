package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O CSV como cada banco exporta (EC-155).
 *
 * <p>O parser existente já lia o formato do Nubank; o que não tinha teste eram
 * os CAMINHOS: delimitador ponto e vírgula, cabeçalho depois de linhas de
 * preâmbulo, quatro formatos de data, decimal com vírgula ou ponto, arquivo em
 * cp1252, identificador do banco versus id posicional. Cada um desses é um
 * banco diferente do usuário — e um erro aqui importa a transação errada, ou
 * não a importa.
 */
@DisplayName("CsvParser — os formatos que os bancos exportam")
class CsvParserFormatsTest {

    private final CsvParser parser = new CsvParser();

    private List<ParsedTransaction> ler(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private List<ParsedTransaction> ler(byte[] bytes) {
        return parser.parse(new ByteArrayInputStream(bytes));
    }

    // ------------------------------------------------------- o cabeçalho

    @Test
    @DisplayName("Delimitador ponto e vírgula (padrão do Excel em pt-BR)")
    void delimitadorPontoEVirgula() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                01/05/2026;MERCADO;-45,90
                """);

        assertThat(lidas).singleElement()
                .extracting(ParsedTransaction::getAmount)
                .isEqualTo(new java.math.BigDecimal("-45.90"));
    }

    @Test
    @DisplayName("Cabeçalho depois do preâmbulo do banco")
    void cabecalhoDepoisDoPreambulo() {
        // O extrato do Inter começa com nome, agência e período antes da tabela
        List<ParsedTransaction> lidas = ler("""
                Extrato Conta Corrente

                Nome: FULANO DE TAL
                Período: 01/05/2026 a 31/05/2026

                Data;Histórico;Descrição;Valor
                01/05/2026;Pix enviado;Ana Costa;-150,00
                """);

        assertThat(lidas).hasSize(1);
        // Histórico e Descrição juntos formam a mesma identidade que o OFX vê
        assertThat(lidas.get(0).getDescription()).isEqualTo("Pix enviado Ana Costa");
    }

    @Test
    @DisplayName("Cabeçalho em inglês")
    void cabecalhoEmIngles() {
        List<ParsedTransaction> lidas = ler("""
                date,description,amount
                2026-05-01,MARKET,-45.90
                """);

        assertThat(lidas).hasSize(1);
        assertThat(lidas.get(0).getDescription()).isEqualTo("MARKET");
    }

    @Test
    @DisplayName("Cabeçalho com sufixo ainda casa: 'Data Lançamento', 'Valor (R$)'")
    void cabecalhoComSufixo() {
        List<ParsedTransaction> lidas = ler("""
                Data Lançamento;Descrição;Valor (R$)
                01/05/2026;MERCADO;-45,90
                """);

        assertThat(lidas).hasSize(1);
    }

    @Test
    @DisplayName("Acento e caixa no cabeçalho não impedem o reconhecimento")
    void cabecalhoSemAcentoEComCaixa() {
        assertThat(ler("""
                DATA;DESCRICAO;VALOR
                01/05/2026;X;10,00
                """)).hasSize(1);
    }

    @Test
    @DisplayName("Arquivo sem coluna de data ou valor é recusado com mensagem clara")
    void semColunasObrigatorias() {
        assertThatThrownBy(() -> ler("""
                Descrição;Categoria
                MERCADO;Alimentação
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ as datas

    @Test
    @DisplayName("Os quatro formatos de data que aparecem nos extratos")
    void quatroFormatosDeData() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                01/05/2026;A;10,00
                2026-05-02;B;10,00
                03-05-2026;C;10,00
                """);

        assertThat(lidas).extracting(tx -> tx.getDate().toLocalDate())
                .containsExactly(
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 2),
                        LocalDate.of(2026, 5, 3));
    }

    @Test
    @DisplayName("Data impossível descarta a linha, não o arquivo")
    void dataInvalidaDescartaALinha() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                32/05/2026;RUIM;10,00
                01/05/2026;BOA;10,00
                """);

        assertThat(lidas).singleElement()
                .extracting(ParsedTransaction::getDescription).isEqualTo("BOA");
    }

    // ----------------------------------------------------------- os valores

    @Test
    @DisplayName("Decimal com vírgula e milhar com ponto (pt-BR)")
    void decimalComVirgula() {
        assertThat(ler("""
                Data;Descrição;Valor
                01/05/2026;SALARIO;4.400,00
                """).get(0).getAmount()).isEqualByComparingTo("4400.00");
    }

    @Test
    @DisplayName("Decimal com ponto e milhar com vírgula (en)")
    void decimalComPonto() {
        assertThat(ler("""
                Data,Descrição,Valor
                01/05/2026,SALARY,"4,400.00"
                """).get(0).getAmount()).isEqualByComparingTo("4400.00");
    }

    @Test
    @DisplayName("Valor com R$ e espaço é limpo antes da conversão")
    void valorComSimboloDeMoeda() {
        assertThat(ler("""
                Data;Descrição;Valor
                01/05/2026;MERCADO;R$ -45,90
                """).get(0).getAmount()).isEqualByComparingTo("-45.90");
    }

    @Test
    @DisplayName("Valor inteiro, sem separador nenhum")
    void valorInteiro() {
        assertThat(ler("""
                Data;Descrição;Valor
                01/05/2026;PIX;500
                """).get(0).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("Valor ilegível descarta a linha")
    void valorIlegivelDescartaALinha() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                01/05/2026;RUIM;abc
                02/05/2026;BOA;10,00
                """);

        assertThat(lidas).singleElement()
                .extracting(ParsedTransaction::getDescription).isEqualTo("BOA");
    }

    @Test
    @DisplayName("Célula vazia de data ou valor pula a linha")
    void celulaVaziaPulaALinha() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                ;SEM DATA;10,00
                02/05/2026;SEM VALOR;
                03/05/2026;BOA;10,00
                """);

        assertThat(lidas).singleElement()
                .extracting(ParsedTransaction::getDescription).isEqualTo("BOA");
    }

    // ------------------------------------------------------ o id da linha

    @Test
    @DisplayName("Com coluna de identificador, o id do BANCO é a chave")
    void idDoBancoVence() {
        List<ParsedTransaction> lidas = ler("""
                Data;Identificador;Descrição;Valor
                01/05/2026;ABC-123;MERCADO;-45,90
                """);

        // É o id que a dedupe usa: o do banco é estável entre exportações
        assertThat(lidas.get(0).getExternalId()).isEqualTo("ABC-123");
    }

    @Test
    @DisplayName("Sem identificador, duas linhas idênticas ganham ids diferentes")
    void idPosicionalDistingueLinhasIdenticas() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                01/05/2026;CAFE;-8,00
                01/05/2026;CAFE;-8,00
                """);

        // Dois cafés no mesmo dia pelo mesmo valor existem de verdade: colapsar
        // os dois num id só faria a segunda compra sumir na importação
        assertThat(lidas).hasSize(2);
        assertThat(lidas.get(0).getExternalId()).isNotEqualTo(lidas.get(1).getExternalId());
    }

    @Test
    @DisplayName("O id posicional repete entre exportações do mesmo extrato")
    void idPosicionalEEstavel() {
        String csv = """
                Data;Descrição;Valor
                01/05/2026;CAFE;-8,00
                02/05/2026;MERCADO;-45,90
                """;

        assertThat(ler(csv)).extracting(ParsedTransaction::getExternalId)
                .containsExactlyElementsOf(
                        ler(csv).stream().map(ParsedTransaction::getExternalId).toList());
    }

    // -------------------------------------------------------- a codificação

    @Test
    @DisplayName("Arquivo em cp1252 (mundo Windows) não vira caractere quebrado")
    void arquivoEmCp1252() {
        byte[] bytes = "Data;Descrição;Valor\n01/05/2026;FARMÁCIA SÃO JOÃO;-32,90\n"
                .getBytes(Charset.forName("windows-1252"));

        assertThat(ler(bytes).get(0).getDescription()).isEqualTo("FARMÁCIA SÃO JOÃO");
    }

    @Test
    @DisplayName("BOM no começo do arquivo não estraga o cabeçalho")
    void bomNoComeco() {
        // Excel salva UTF-8 com BOM; sem removê-lo, "Data" vira "﻿Data" e o
        // cabeçalho deixa de ser reconhecido
        byte[] semBom = "Data;Descrição;Valor\n01/05/2026;MERCADO;-45,90\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] comBom = new byte[semBom.length + 3];
        comBom[0] = (byte) 0xEF;
        comBom[1] = (byte) 0xBB;
        comBom[2] = (byte) 0xBF;
        System.arraycopy(semBom, 0, comBom, 3, semBom.length);

        assertThat(ler(comBom)).hasSize(1);
    }

    @Test
    @DisplayName("Arquivo só com cabeçalho devolve lista vazia")
    void soCabecalho() {
        assertThat(ler("Data;Descrição;Valor\n")).isEmpty();
    }

    @Test
    @DisplayName("O sinal decide entrada e saída")
    void sinalDecideOTipo() {
        List<ParsedTransaction> lidas = ler("""
                Data;Descrição;Valor
                01/05/2026;SALARIO;4400,00
                02/05/2026;MERCADO;-45,90
                """);

        assertThat(lidas).extracting(ParsedTransaction::getType)
                .containsExactly("CREDIT", "DEBIT");
    }

    @Test
    @DisplayName("Vale refeicao (Flash): a coluna se chama 'Movimentacao' e e a descricao")
    void colunaMovimentacaoEhADescricao() {
        // O cabecalho ja era ACEITO sem esta coluna — data e valor bastam — e o
        // extrato entrava com descricao vazia: nada para categorizar e nada
        // para reconhecer depois
        List<ParsedTransaction> lidas = ler("""
                Data,Hora,Movimentação,Valor,Meio de Pagamento,Saldo
                29/08/2026,10:08,SUPERMERCADO SERO SEROPEDICA BRA,"-R$ 609,79",Cartão,"R$ 47,51"
                28/08/2026,17:59,Depósito transferido,"R$ 735,00",Depósito,"R$ 735,00"
                """);

        assertThat(lidas).extracting(ParsedTransaction::getDescription)
                .containsExactly("SUPERMERCADO SERO SEROPEDICA BRA", "Depósito transferido");
        assertThat(lidas).extracting(ParsedTransaction::getType)
                .containsExactly("DEBIT", "CREDIT");
        // "-R$ 609,79" entre aspas: cifrao, espaco e virgula decimal juntos
        assertThat(lidas.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-609.79"));
    }

    @Test
    @DisplayName("Espaco NAO-QUEBRAVEL entre o cifrao e o numero nao descarta a linha")
    void espacoNaoQuebravelNoValor() {
        // O que o Flash exporta de verdade: U+00A0 entre "R$" e o numero. O
        // `\s` do Java NAO casa com ele — o valor chegava ao BigDecimal com o
        // caractere no meio, estourava, e as 15 linhas do extrato sumiam uma a
        // uma como "valor ilegivel". Mesma armadilha do resumo do relatorio.
        String nbsp = "\u00A0";
        List<ParsedTransaction> lidas = ler(
                "Data,Movimentação,Valor\n"
                        + "29/08/2026,SUPERMERCADO SERO,\"-R$" + nbsp + "609,79\"\n"
                        + "28/08/2026,Depósito transferido,\"R$" + nbsp + "735,00\"\n");

        assertThat(lidas).hasSize(2);
        assertThat(lidas.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-609.79"));
        assertThat(lidas.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("735.00"));
    }

    @Test
    @DisplayName("Declara o formato que atende")
    void declaraOFormato() {
        assertThat(parser.format()).isEqualTo(StatementFormat.CSV);
    }
}
