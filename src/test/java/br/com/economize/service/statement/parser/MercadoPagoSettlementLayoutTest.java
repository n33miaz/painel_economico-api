package br.com.economize.service.statement.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O relatório de liberações do Mercado Pago (settlement_v2) entrando pelo
 * leitor de XLSX.
 *
 * <p>As linhas de exemplo são as do arquivo real de 06/09/2026 (valores e ids
 * verdadeiros, sem dado pessoal): uma transferência recebida, uma compra no
 * cartão de crédito com canal "Mercado Livre" e um cashback pago em saldo.
 */
@DisplayName("XlsxParser — relatório de liberações do Mercado Pago")
class MercadoPagoSettlementLayoutTest {

    private static final String[] CABECALHO = {
            "ID DA TRANSAÇÃO NO MERCADO PAGO", "TIPO DE MEIO DE PAGAMENTO", "TIPO DE TRANSAÇÃO",
            "VALOR DA COMPRA", "DATA DE ORIGEM", "TARIFAS", "DATA DE APROVAÇÃO",
            "VALOR LÍQUIDO DA TRANSAÇÃO", "IMPOSTOS COBRADOS POR RETENÇÕES DE IIBB",
            "DATA DE LIBERAÇÃO DO DINHEIRO", "CANAL DE VENDA", "PLATAFORMA DE PAGAMENTO"};

    private static final String[][] LINHAS = {
            {"176548196337", "Transferência bancária", "Pagamento aprovado", "325.41",
                    "2026-09-05T19:32:10.000-03:00", "0.00", "2026-09-05T19:32:10.000-03:00", "325.41",
                    "0.00", "2026-09-05T19:32:10.000-03:00", "", ""},
            {"172794810175", "Cartão de crédito", "Pagamento aprovado", "-641.14",
                    "2026-08-13T20:04:32.000-03:00", "0.00", "2026-08-13T20:04:35.000-03:00", "-641.14",
                    "0.00", "2026-08-13T20:04:35.000-03:00", "Mercado Livre", ""},
            {"171824453863", "available_money", "Cashback", "169.98",
                    "2026-08-08T12:24:32.000-03:00", "0.00", "2026-08-13T10:13:46.000-03:00", "169.98",
                    "0.00", "2026-08-13T10:13:46.000-03:00", "", ""}};

    private final XlsxParser parser = new XlsxParser();

    /** Planilha de verdade, como o export: tudo em célula de TEXTO. */
    private static InputStream planilha(String[] cabecalho, String[][] linhas) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("sheet0");
            Row header = sheet.createRow(0);
            for (int i = 0; i < cabecalho.length; i++) header.createCell(i).setCellValue(cabecalho[i]);
            for (int r = 0; r < linhas.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < linhas[r].length; c++) row.createCell(c).setCellValue(linhas[r][c]);
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    @DisplayName("reconhece o cabeçalho do relatório e lê as três linhas")
    void leORelatorioInteiro() throws IOException {
        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, LINHAS));

        assertThat(txs).hasSize(3);
        assertThat(txs).extracting(ParsedTransaction::getExternalId)
                .containsExactly("MP-176548196337", "MP-172794810175", "MP-171824453863");
    }

    @Test
    @DisplayName("o id do Mercado Pago vira o id externo — reexportar não duplica")
    void idExternoVemDoProvedor() throws IOException {
        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, LINHAS));

        assertThat(txs.get(0).getExternalId()).isEqualTo("MP-176548196337");
    }

    @Test
    @DisplayName("data é o dia civil da APROVAÇÃO no fuso do relatório, à meia-noite UTC")
    void dataEDiaDaAprovacao() throws IOException {
        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, LINHAS));

        // o cashback teve origem em 08/08 e aprovação em 13/08: vale a aprovação,
        // que é quando o dinheiro passou a existir para o usuário
        assertThat(txs.get(2).getDate())
                .isEqualTo(LocalDate.of(2026, 8, 13).atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThat(txs.get(0).getDate().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    @DisplayName("valor líquido e sinal decidem o tipo")
    void valorESinal() throws IOException {
        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, LINHAS));

        assertThat(txs.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("325.41"));
        assertThat(txs.get(0).getType()).isEqualTo("CREDIT");
        assertThat(txs.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("-641.14"));
        assertThat(txs.get(1).getType()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("a descrição junta tipo, meio de pagamento e canal — e traduz o token de saldo")
    void descricaoLegivel() throws IOException {
        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, LINHAS));

        assertThat(txs.get(0).getDescription()).isEqualTo("Pagamento aprovado · Transferência bancária");
        assertThat(txs.get(1).getDescription())
                .isEqualTo("Pagamento aprovado · Cartão de crédito · Mercado Livre");
        // "available_money" é token interno do provedor, não descrição
        assertThat(txs.get(2).getDescription()).isEqualTo("Cashback · saldo em conta");
    }

    @Test
    @DisplayName("cabeçalho com a codificação quebrada do export ainda é reconhecido")
    void cabecalhoComCodificacaoQuebrada() throws IOException {
        String[] quebrado = CABECALHO.clone();
        quebrado[0] = "ID DA TRANSA��O NO MERCADO PAGO";
        quebrado[2] = "TIPO DE TRANSA��O";
        quebrado[6] = "DATA DE APROVA��O";
        quebrado[7] = "VALOR L�QUIDO DA TRANSA��O";

        List<ParsedTransaction> txs = parser.parse(planilha(quebrado, LINHAS));

        assertThat(txs).hasSize(3);
    }

    @Test
    @DisplayName("sem a coluna de aprovação, vale a data de origem")
    void semAprovacaoUsaOrigem() throws IOException {
        String[] semAprovacao = {"ID DA TRANSAÇÃO NO MERCADO PAGO", "TIPO DE MEIO DE PAGAMENTO",
                "TIPO DE TRANSAÇÃO", "VALOR DA COMPRA", "DATA DE ORIGEM"};
        String[][] linhas = {{"1", "Pix", "Pagamento aprovado", "10.00", "2026-08-08T12:24:32.000-03:00"}};

        List<ParsedTransaction> txs = parser.parse(planilha(semAprovacao, linhas));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getDate().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(txs.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("linha sem id ou sem valor é pulada, não derruba o arquivo")
    void linhaIncompletaEPulada() throws IOException {
        String[][] linhas = {LINHAS[0],
                {"", "Pix", "Pagamento aprovado", "5.00", "2026-08-08T12:24:32.000-03:00", "0.00",
                        "2026-08-08T12:24:32.000-03:00", "5.00", "0.00", "", "", ""},
                {"999", "Pix", "Pagamento aprovado", "", "2026-08-08T12:24:32.000-03:00", "0.00",
                        "2026-08-08T12:24:32.000-03:00", "abc", "0.00", "", "", ""}};

        List<ParsedTransaction> txs = parser.parse(planilha(CABECALHO, linhas));

        assertThat(txs).hasSize(1);
    }

    @Test
    @DisplayName("planilha comum de banco continua caindo no leitor genérico")
    void naoSequestraOLeitorGenerico() throws IOException {
        String[] generico = {"Data", "Descrição", "Valor"};
        String[][] linhas = {{"05/09/2026", "Pix recebido", "100,00"}};

        List<ParsedTransaction> txs = parser.parse(planilha(generico, linhas));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getExternalId()).startsWith("XLSX-");
    }
}
