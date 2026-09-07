package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class XlsxParser implements StatementParserStrategy {

    @Override
    public StatementFormat format() {
        return StatementFormat.XLSX;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        List<ParsedTransaction> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(input)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return out;
            Map<String, Integer> header = readHeader(sheet.getRow(sheet.getFirstRowNum()));
            // Layouts com cabeçalho próprio vêm ANTES do genérico: o relatório do
            // Mercado Pago não tem coluna "Data" nem "Valor" e cairia no erro de
            // "colunas obrigatórias" mesmo sendo um extrato perfeitamente legível
            MercadoPagoSettlementLayout mercadoPago = MercadoPagoSettlementLayout.detect(header);
            if (mercadoPago != null) {
                for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    ParsedTransaction tx = mercadoPago.map(row);
                    if (tx != null) out.add(tx);
                }
                return out;
            }
            int dateCol = pick(header, "data", "date");
            int descCol = pick(header, "descrição", "descricao", "description", "histórico", "historico");
            int amountCol = pick(header, "valor", "amount");
            if (dateCol < 0 || amountCol < 0) {
                throw new IllegalArgumentException("XLSX precisa ter colunas Data e Valor");
            }
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                OffsetDateTime date = readDate(row.getCell(dateCol));
                BigDecimal value = readNumber(row.getCell(amountCol));
                String desc = descCol >= 0 ? readString(row.getCell(descCol)) : "";
                if (date == null || value == null) continue;
                out.add(ParsedTransaction.builder()
                        .externalId("XLSX-" + i + "-" + date)
                        .type(value.signum() >= 0 ? "CREDIT" : "DEBIT")
                        .amount(value)
                        .description(desc != null ? desc : "")
                        .date(date)
                        .build());
            }
        } catch (org.apache.poi.UnsupportedFileFormatException e) {
            // .xls (formato OLE2 antigo) cai aqui — a mensagem crua do POI é indecifrável
            throw new IllegalArgumentException(
                    "Planilhas .xls antigas não são suportadas — exporte o extrato como .xlsx");
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler XLSX", e);
        }
        return out;
    }

    private Map<String, Integer> readHeader(Row row) {
        Map<String, Integer> map = new HashMap<>();
        if (row == null) return map;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell == null) continue;
            map.put(cell.getStringCellValue().toLowerCase().trim(), i);
        }
        return map;
    }

    private int pick(Map<String, Integer> header, String... keys) {
        for (String key : keys) {
            Integer idx = header.get(key);
            if (idx != null) return idx;
        }
        return -1;
    }

    // bancos exportam a coluna de data ora como célula de data, ora como texto
    private static final List<DateTimeFormatter> TEXT_DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    private OffsetDateTime readDate(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            // getDateCellValue() interpreta o serial do Excel no fuso da JVM: num
            // container fora de UTC o dia 01 viraria o último dia do mês anterior
            // e a transação cairia no mês errado da análise
            return cell.getLocalDateTimeCellValue().toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
            String raw = cell.getStringCellValue().trim();
            for (DateTimeFormatter format : TEXT_DATE_FORMATS) {
                try {
                    return LocalDate.parse(raw, format).atStartOfDay().atOffset(ZoneOffset.UTC);
                } catch (Exception ignored) {
                    // tenta o próximo formato
                }
            }
        }
        return null;
    }

    private BigDecimal readNumber(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                try {
                    yield new BigDecimal(cell.getStringCellValue().replace(",", ".").trim());
                } catch (Exception e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private String readString(Cell cell) {
        if (cell == null) return "";
        cell.setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        return cell.getStringCellValue();
    }
}
