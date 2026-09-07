package br.com.economize.service.statement.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Relatório de liberações do Mercado Pago ("settlement_v2-…xlsx"), o único
 * arquivo tabular que o Mercado Pago exporta — o extrato em PDF já tem leitor
 * próprio ({@link MercadoPagoPdfLayout}), mas o PDF quebra descrição em três
 * linhas e o XLSX chega inteiro, com o id da transação no provedor.
 *
 * <p>Por que um layout próprio, e não o leitor genérico de XLSX: as colunas
 * não se chamam "Data" nem "Valor" ("DATA DE APROVAÇÃO", "VALOR LÍQUIDO DA
 * TRANSAÇÃO"), as datas vêm como TEXTO em ISO com fuso ("2026-09-05T19:32:10.000-03:00")
 * e não existe coluna de descrição — a identidade do lançamento está espalhada
 * em "TIPO DE TRANSAÇÃO", "TIPO DE MEIO DE PAGAMENTO" e "CANAL DE VENDA".
 *
 * <p>O id do Mercado Pago vira o id externo ("MP-" + id). É ele que faz a
 * reexportação de uma janela maior não duplicar o que já entrou — e é o mesmo
 * prefixo usado na importação manual de 06/09/2026, de propósito.
 *
 * <p>Limite conhecido, declarado na descrição: uma compra no cartão de crédito
 * aparece aqui como UMA linha no dia da compra, ainda que o cartão a divida em
 * parcelas. O relatório não diz quantas; quem sabe é a fatura do cartão. Por
 * isso a linha ganha o texto "cartão de crédito" — o usuário vê que aquele
 * valor pertence à fatura, e não ao saldo da conta.
 */
final class MercadoPagoSettlementLayout {

    // Padrões, e não literais: quando a codificação do export vem quebrada, cada
    // letra acentuada chega como U+FFFD e some na normalização — "transação"
    // vira "transao". O [a-z]{0,2} no lugar das letras acentuadas casa a grafia
    // certa e a mutilada com a mesma expressão.
    private static final Pattern COL_ID = Pattern.compile("id da transa[a-z]{0,2}o no mercado pago");
    private static final Pattern COL_TIPO = Pattern.compile("tipo de transa[a-z]{0,2}o");
    private static final Pattern COL_MEIO = Pattern.compile("tipo de meio de pagamento");
    private static final Pattern COL_CANAL = Pattern.compile("canal de venda");
    private static final Pattern COL_VALOR_LIQUIDO = Pattern.compile("valor l[a-z]{0,1}quido da transa[a-z]{0,2}o");
    private static final Pattern COL_VALOR_COMPRA = Pattern.compile("valor da compra");
    private static final Pattern COL_DATA_APROVACAO = Pattern.compile("data de aprova[a-z]{0,2}o");
    private static final Pattern COL_DATA_ORIGEM = Pattern.compile("data de origem");

    private final int idCol;
    private final int tipoCol;
    private final int meioCol;
    private final int canalCol;
    private final int valorCol;
    private final int dataCol;

    private MercadoPagoSettlementLayout(int idCol, int tipoCol, int meioCol, int canalCol,
                                        int valorCol, int dataCol) {
        this.idCol = idCol;
        this.tipoCol = tipoCol;
        this.meioCol = meioCol;
        this.canalCol = canalCol;
        this.valorCol = valorCol;
        this.dataCol = dataCol;
    }

    /**
     * Reconhece o cabeçalho pelas colunas que só este relatório tem. O mapa vem
     * do leitor genérico (nomes já em minúsculas); aqui os acentos saem também,
     * porque o mesmo arquivo já chegou com "TRANSAÇÃO" e com "TRANSA��O" —
     * a codificação do export não é confiável, a estrutura é.
     */
    static MercadoPagoSettlementLayout detect(Map<String, Integer> header) {
        int id = -1, tipo = -1, meio = -1, canal = -1, liquido = -1, compra = -1, aprovacao = -1, origem = -1;
        for (Map.Entry<String, Integer> entry : header.entrySet()) {
            String name = stripAccents(entry.getKey());
            int idx = entry.getValue();
            if (COL_ID.matcher(name).matches()) id = idx;
            else if (COL_TIPO.matcher(name).matches()) tipo = idx;
            else if (COL_MEIO.matcher(name).matches()) meio = idx;
            else if (COL_CANAL.matcher(name).matches()) canal = idx;
            else if (COL_VALOR_LIQUIDO.matcher(name).matches()) liquido = idx;
            else if (COL_VALOR_COMPRA.matcher(name).matches()) compra = idx;
            else if (COL_DATA_APROVACAO.matcher(name).matches()) aprovacao = idx;
            else if (COL_DATA_ORIGEM.matcher(name).matches()) origem = idx;
            // o resto (tarifas, impostos, plataforma) não interessa
        }
        // O líquido é o que de fato mexeu no saldo; o valor da compra é reserva
        // para exportações antigas sem a coluna
        int valor = liquido >= 0 ? liquido : compra;
        // Aprovação é quando o dinheiro passou a existir para o usuário; a
        // origem é o clique na compra, que pode ser dias antes
        int data = aprovacao >= 0 ? aprovacao : origem;
        if (id < 0 || tipo < 0 || valor < 0 || data < 0) return null;
        return new MercadoPagoSettlementLayout(id, tipo, meio, canal, valor, data);
    }

    ParsedTransaction map(Row row) {
        String id = text(row.getCell(idCol));
        BigDecimal amount = number(row.getCell(valorCol));
        OffsetDateTime date = date(row.getCell(dataCol));
        if (id == null || id.isBlank() || amount == null || date == null) return null;

        List<String> parts = new ArrayList<>();
        String tipo = text(row.getCell(tipoCol));
        if (tipo != null && !tipo.isBlank()) parts.add(tipo.trim());
        String meio = humanizePaymentMethod(text(row.getCell(meioCol)));
        if (meio != null) parts.add(meio);
        String canal = canalCol >= 0 ? text(row.getCell(canalCol)) : null;
        if (canal != null && !canal.isBlank()) parts.add(canal.trim());
        String description = parts.isEmpty() ? "Mercado Pago" : String.join(" · ", parts);

        return ParsedTransaction.builder()
                .externalId("MP-" + id.trim())
                .type(amount.signum() >= 0 ? "CREDIT" : "DEBIT")
                .amount(amount)
                .description(description)
                .date(date)
                .build();
    }

    /**
     * O meio de pagamento vem como token interno do Mercado Pago quando é saldo
     * ("available_money", "account_money"); em português quando é externo
     * ("Cartão de crédito", "Transferência bancária"). Token cru na descrição
     * do usuário não é descrição.
     */
    private static String humanizePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String token = raw.trim().toLowerCase(Locale.ROOT);
        return switch (token) {
            case "available_money", "account_money" -> "saldo em conta";
            default -> raw.trim();
        };
    }

    private static final DateTimeFormatter TEXT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Data em ISO com o fuso do próprio relatório ("2026-09-05T19:32:10.000-03:00"):
     * o dia é o dia CIVIL naquele fuso, e não o dia em UTC — às 22h de Brasília
     * já é dia seguinte em UTC, e a transação cairia no mês errado da análise.
     * Depois de escolhido o dia, ele entra à meia-noite UTC como nos outros
     * leitores, para a chave do ledger de reconciliação ser a mesma.
     */
    private static OffsetDateTime date(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        }
        String raw = text(cell);
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        try {
            return OffsetDateTime.parse(raw).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // segue para os formatos sem fuso
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw)
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
            // idem
        }
        try {
            return LocalDate.parse(raw, TEXT_DATE).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal number(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
        String raw = text(cell);
        if (raw == null || raw.isBlank()) return null;
        try {
            // o export usa ponto decimal ("325.41"); vírgula fica coberta por precaução
            return new BigDecimal(raw.trim().replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                // ids longos chegam numéricos em algumas exportações; sem isto virariam "1.76548196337E11"
                yield v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static String stripAccents(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                // a codificação quebrada do export troca o caractere acentuado por U+FFFD
                .replace("�", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
