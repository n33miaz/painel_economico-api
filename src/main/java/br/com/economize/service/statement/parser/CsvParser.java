package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CSVs de banco brasileiro não são uniformes: o Inter põe 4 linhas de resumo
 * antes do cabeçalho e separa a operação (Histórico) da contraparte
 * (Descrição); o Nubank traz uma coluna Identificador com o mesmo UUID do
 * FITID do OFX. Este parser localiza o cabeçalho onde estiver, resolve as
 * colunas por nome flexível e usa o ID do banco quando ele existe.
 */
@Slf4j
@Component
public class CsvParser implements StatementParserStrategy {

    private static final DateTimeFormatter[] FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
    };

    private static final int HEADER_SEARCH_LIMIT = 20;
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");

    @Override
    public StatementFormat format() {
        return StatementFormat.CSV;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        String content = decode(input);
        List<String> lines = content.lines().toList();

        HeaderInfo header = findHeader(lines);
        if (header == null) {
            throw new IllegalArgumentException(
                    "CSV sem cabeçalho reconhecível — esperado colunas de Data e Valor");
        }

        List<ParsedTransaction> result = new ArrayList<>();
        String body = String.join("\n", lines.subList(header.lineIndex + 1, lines.size()));
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(header.delimiter)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .build();

        // n-ésima ocorrência do mesmo (data, valor, descrição) dentro do arquivo:
        // torna o id posicional estável entre exportações de janelas diferentes
        Map<String, Integer> occurrences = new HashMap<>();

        try (CSVParser parser = new CSVParser(new StringReader(body), format)) {
            for (CSVRecord rec : parser) {
                ParsedTransaction tx = mapRow(rec, header, occurrences);
                if (tx != null) result.add(tx);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler CSV", e);
        }
        return result;
    }

    private ParsedTransaction mapRow(CSVRecord rec, HeaderInfo header, Map<String, Integer> occurrences) {
        String rawDate = cell(rec, header.dateIdx);
        String rawAmount = cell(rec, header.amountIdx);
        if (rawDate == null || rawAmount == null) return null;

        OffsetDateTime date = parseDate(rawDate);
        if (date == null) {
            log.warn("Data CSV inválida '{}', ignorando linha", rawDate);
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(normalizeNumber(rawAmount));
        } catch (Exception e) {
            log.warn("Valor CSV inválido '{}', ignorando linha", rawAmount);
            return null;
        }

        // Inter: Histórico ("Pix enviado") + Descrição ("Fulano") juntos formam a
        // mesma identidade que o app de notificação/OFX enxerga
        StringBuilder desc = new StringBuilder();
        for (int idx : header.descIdxs) {
            String part = cell(rec, idx);
            if (part != null) {
                if (desc.length() > 0) desc.append(' ');
                desc.append(part);
            }
        }
        String description = desc.toString().trim();

        String bankId = header.idIdx >= 0 ? cell(rec, header.idIdx) : null;
        String externalId = bankId != null ? bankId : positionalId(date, value, description, occurrences);

        return ParsedTransaction.builder()
                .externalId(externalId)
                .type(value.signum() >= 0 ? "CREDIT" : "DEBIT")
                .amount(value)
                .description(description)
                .date(date)
                .build();
    }

    private String positionalId(OffsetDateTime date, BigDecimal value, String description,
                                Map<String, Integer> occurrences) {
        String day = date.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        String amount = value.toPlainString();
        // String.hashCode é estável por especificação — a chave sobrevive a reexports
        String descHash = Integer.toHexString(description.toLowerCase(Locale.ROOT).hashCode());
        String key = day + "|" + amount + "|" + descHash;
        int occurrence = occurrences.merge(key, 1, Integer::sum) - 1;
        return "CSV-" + day + "-" + amount + "-" + descHash + "-" + occurrence;
    }

    // --- Localização do cabeçalho ---

    private HeaderInfo findHeader(List<String> lines) {
        int limit = Math.min(lines.size(), HEADER_SEARCH_LIMIT);
        for (int i = 0; i < limit; i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) continue;
            for (char delimiter : new char[]{';', ','}) {
                HeaderInfo info = tryHeader(line, i, delimiter);
                if (info != null) return info;
            }
        }
        return null;
    }

    private HeaderInfo tryHeader(String line, int lineIndex, char delimiter) {
        String[] cells = line.split(Pattern.quote(String.valueOf(delimiter)), -1);
        if (cells.length < 2) return null;

        int dateIdx = -1;
        int amountIdx = -1;
        int idIdx = -1;
        int historicoIdx = -1;
        int descricaoIdx = -1;

        for (int i = 0; i < cells.length; i++) {
            String name = normalizeHeader(cells[i]);
            if (name.isEmpty()) continue;
            if (dateIdx < 0 && (name.equals("data") || name.equals("date") || name.startsWith("data "))) {
                dateIdx = i;
            } else if (amountIdx < 0 && (name.equals("valor") || name.equals("amount") || name.startsWith("valor "))) {
                amountIdx = i;
            } else if (idIdx < 0 && (name.equals("identificador") || name.equals("id") || name.equals("identifier"))) {
                idIdx = i;
            } else if (historicoIdx < 0 && name.startsWith("historico")) {
                historicoIdx = i;
            } else if (descricaoIdx < 0 && (name.startsWith("descricao") || name.equals("description")
                    || name.equals("title") || name.startsWith("lancamento") || name.startsWith("estabelecimento")
                    // "Movimentacao" e como o Flash (vale refeicao) chama a
                    // coluna. Sem ela, o cabecalho AINDA era aceito — data e
                    // valor bastam — e as 15 linhas entravam com descricao
                    // VAZIA: nada para categorizar, nada para reconhecer, um
                    // extrato importado que nao diz o que foi comprado
                    || name.startsWith("movimentacao"))) {
                descricaoIdx = i;
            }
        }

        if (dateIdx < 0 || amountIdx < 0) return null;

        List<Integer> descIdxs = new ArrayList<>();
        if (historicoIdx >= 0) descIdxs.add(historicoIdx);
        if (descricaoIdx >= 0) descIdxs.add(descricaoIdx);

        return new HeaderInfo(lineIndex, delimiter, dateIdx, amountIdx, idIdx, descIdxs);
    }

    private String normalizeHeader(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return ACCENTS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    // --- Auxiliares ---

    private String cell(CSVRecord rec, int idx) {
        if (idx < 0 || idx >= rec.size()) return null;
        String value = rec.get(idx);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeNumber(String raw) {
        String value = raw.replace("R$", "").replaceAll("\\s+", "").trim();
        int lastComma = value.lastIndexOf(',');
        int lastDot = value.lastIndexOf('.');
        if (lastComma > lastDot) {
            // Vírgula como decimal (PT-BR)
            value = value.replace(".", "").replace(",", ".");
        } else if (lastDot > lastComma) {
            // Ponto como decimal (EN)
            value = value.replace(",", "");
        }
        return value;
    }

    private OffsetDateTime parseDate(String raw) {
        for (DateTimeFormatter fmt : FORMATS) {
            try {
                return LocalDate.parse(raw, fmt).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // tenta o próximo formato
            }
        }
        return null;
    }

    /**
     * UTF-8 estrito primeiro; se houver byte inválido, o arquivo veio do mundo
     * Windows (cp1252) — melhor que deixar replacement chars na descrição.
     */
    private String decode(InputStream input) {
        try {
            byte[] bytes = input.readAllBytes();
            int offset = bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF
                    ? 3 : 0;
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(buffer)
                        .toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, offset, bytes.length - offset, Charset.forName("windows-1252"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler CSV", e);
        }
    }

    private record HeaderInfo(int lineIndex, char delimiter, int dateIdx, int amountIdx,
                              int idIdx, List<Integer> descIdxs) {
    }
}
