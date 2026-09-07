package br.com.economize.service.investment;

import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.InvestmentPosition.Type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Traduz uma posição como a API do Pluggy a devolve ({@code GET /investments})
 * para o vocabulário do produto. Pura: recebe o {@code Map} cru e devolve os
 * campos prontos, sem tocar em banco nem em rede — é por isso que o teste dela
 * consegue montar um JSON realista de CDB, Tesouro e ETF e conferir cada campo.
 *
 * <p>DEFENSIVA de ponta a ponta. O contrato do Pluggy varia por tipo de ativo
 * e por conector: campo que existe no CDB não existe na ação, número vem como
 * inteiro, decimal ou texto, e uma data ilegível não pode derrubar o sync das
 * outras trinta posições. Cada leitura tolera ausência e formato inesperado
 * devolvendo nulo — e nulo aqui significa "o provedor não disse", que é o que
 * a resposta declara, nunca zero.
 *
 * <p>O mapa de tipos, na ordem em que as regras são testadas:
 * <ul>
 * <li>subtype {@code TREASURY} (venha como FIXED_INCOME ou não) → TREASURY, com
 * o indexador lido do NOME ("Selic", "IPCA", "Prefixado");</li>
 * <li>{@code FIXED_INCOME} → FIXED_INCOME, subtipo do provedor (CDB, LCI, LCA,
 * CRI…) e indexador do {@code rateType};</li>
 * <li>{@code EQUITY} com subtipo REAL_ESTATE_FUND → FUND/FII (o app trata FII
 * como fundo, e a personalização precisa do tópico "fiis"); com subtipo ETF →
 * ETF; o resto → EQUITY;</li>
 * <li>{@code ETF} → ETF; {@code MUTUAL_FUND} → FUND;</li>
 * <li>{@code SECURITY} → PENSION: no Pluggy, "security" é previdência
 * (PGBL/VGBL), não título;</li>
 * <li>{@code COE} e {@code OTHER} → OTHER, guardando o subtipo.</li>
 * </ul>
 */
final class PluggyInvestmentMapper {

    private PluggyInvestmentMapper() {
    }

    static final int NAME_MAX = 160;
    static final int CODE_MAX = 32;
    static final int SUBTYPE_MAX = 32;
    static final int RATE_MAX = 40;

    /** A posição já traduzida, pronta para virar (ou atualizar) uma linha. */
    record Mapped(String providerId, String name, String code, String issuer,
                  Type type, String subtype, Indexer indexer, String rate, String currency,
                  BigDecimal quantity, BigDecimal unitPrice,
                  BigDecimal investedAmount, BigDecimal currentValue,
                  LocalDate maturityDate, LocalDate positionDate) {
    }

    /**
     * Nulo quando a posição não tem {@code id}: sem ele não há como fazer o
     * upsert nem distinguir a posição entre duas syncs — gravar geraria uma
     * linha nova a cada sincronização.
     */
    static Mapped map(Map<String, Object> raw) {
        if (raw == null) return null;
        String id = text(raw.get("id"));
        if (id == null) return null;

        String rawType = upper(text(raw.get("type")));
        String rawSubtype = upper(text(raw.get("subtype")));
        String name = text(raw.get("name"));
        String code = text(raw.get("code"));
        if (name == null) name = code != null ? code : "Investimento";
        String plainName = plain(name);

        Type type;
        String subtype;
        Indexer indexer;
        if ("TREASURY".equals(rawSubtype) || (rawType != null && rawType.contains("TREASURY"))) {
            type = Type.TREASURY;
            indexer = treasuryIndexer(plainName, raw);
            subtype = indexer == null ? "TESOURO" : "TESOURO_" + indexer.name();
        } else if ("FIXED_INCOME".equals(rawType)) {
            type = Type.FIXED_INCOME;
            subtype = rawSubtype;
            indexer = rateIndexer(raw, plainName);
        } else if ("EQUITY".equals(rawType)) {
            if (rawSubtype != null && rawSubtype.contains("REAL_ESTATE")) {
                type = Type.FUND;
                subtype = "FII";
            } else if ("ETF".equals(rawSubtype)) {
                type = Type.ETF;
                subtype = "ETF";
            } else {
                type = Type.EQUITY;
                subtype = rawSubtype;
            }
            indexer = marketIndexer(raw);
        } else if ("ETF".equals(rawType)) {
            type = Type.ETF;
            subtype = rawSubtype != null ? rawSubtype : "ETF";
            indexer = marketIndexer(raw);
        } else if ("MUTUAL_FUND".equals(rawType)) {
            type = Type.FUND;
            subtype = rawSubtype;
            // fundo de renda fixa/DI acompanha o CDI; os outros não têm indexador
            indexer = rateIndexer(raw, plainName);
        } else if ("SECURITY".equals(rawType)) {
            type = Type.PENSION;
            subtype = rawSubtype;
            indexer = rateIndexer(raw, plainName);
        } else if ("CRYPTO".equals(rawType) || "CRYPTOCURRENCY".equals(rawType)) {
            type = Type.CRYPTO;
            subtype = rawSubtype;
            indexer = Indexer.NONE;
        } else {
            type = Type.OTHER;
            subtype = "COE".equals(rawType) && rawSubtype == null ? "COE" : rawSubtype;
            indexer = rateIndexer(raw, plainName);
        }

        BigDecimal quantity = decimal(raw.get("quantity"));
        BigDecimal unitPrice = decimal(raw.get("value"));
        BigDecimal currentValue = decimal(raw.get("balance"));
        if (currentValue == null) currentValue = decimal(raw.get("amount"));
        if (currentValue == null && quantity != null && unitPrice != null) {
            currentValue = quantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
        }

        LocalDate positionDate = date(raw.get("date"));

        return new Mapped(
                id,
                truncate(name, NAME_MAX),
                truncate(upper(code), CODE_MAX),
                text(raw.get("issuer")),
                type,
                truncate(subtype, SUBTYPE_MAX),
                indexer,
                truncate(rateLabel(raw, indexer), RATE_MAX),
                currency(raw.get("currencyCode")),
                quantity,
                unitPrice,
                decimal(raw.get("amountOriginal")),
                currentValue,
                date(raw.get("dueDate")),
                positionDate);
    }

    /**
     * O Tesouro diz o indexador no NOME do título ("Tesouro Selic 2029",
     * "Tesouro IPCA+ 2035", "Tesouro Prefixado 2027"); o {@code rateType} vem
     * de rede, para conector que só informa ali.
     */
    private static Indexer treasuryIndexer(String plainName, Map<String, Object> raw) {
        if (plainName.contains("SELIC")) return Indexer.SELIC;
        if (plainName.contains("IPCA")) return Indexer.IPCA;
        if (plainName.contains("PREFIXADO") || plainName.contains("PRE FIXADO") || plainName.contains("PRE-FIXADO")) {
            return Indexer.PREFIXADO;
        }
        return rateIndexer(raw, "");
    }

    /**
     * Indexador da renda fixa: {@code rateType} primeiro (é o campo feito para
     * isso), depois a taxa quando ela vem como TEXTO ("100% do CDI") e por fim
     * o nome ("CDB 110% CDI"). Nulo quando nada diz — e nulo é a verdade, não
     * PREFIXADO por eliminação.
     */
    private static Indexer rateIndexer(Map<String, Object> raw, String plainName) {
        Indexer fromType = indexerOf(upper(text(raw.get("rateType"))));
        if (fromType != null) return fromType;
        if (raw.get("rate") instanceof String s && decimal(s) == null) {
            Indexer fromRate = indexerOf(plain(s));
            if (fromRate != null) return fromRate;
        }
        return indexerOf(plainName);
    }

    private static Indexer indexerOf(String text) {
        if (text == null || text.isBlank()) return null;
        if (text.contains("CDI") || text.contains("DI ")) return Indexer.CDI;
        if (text.contains("SELIC")) return Indexer.SELIC;
        if (text.contains("IPCA") || text.contains("INFLAC")) return Indexer.IPCA;
        if (text.contains("PREFIXADO") || text.contains("PRE FIXADO") || text.contains("PRE-FIXADO")
                || "FIXED".equals(text)) {
            return Indexer.PREFIXADO;
        }
        if (text.contains("USD") || text.contains("DOLAR")) return Indexer.USD;
        return null;
    }

    /** Ação e ETF não têm indexador; o que importa é a MOEDA em que são cotados. */
    private static Indexer marketIndexer(Map<String, Object> raw) {
        return "USD".equals(currency(raw.get("currencyCode"))) ? Indexer.USD : Indexer.NONE;
    }

    /**
     * Texto de apresentação da taxa. O provedor a espalha em três campos com
     * semânticas diferentes — {@code rate} é o percentual do indexador (110 em
     * "110% CDI"), {@code fixedAnnualRate} a parte fixa (6,2 em "IPCA + 6,2%") e
     * {@code annualRate} o rendimento anual — e o app só precisa mostrá-la.
     * Alguns conectores já mandam {@code rate} pronto como texto; nesse caso ele
     * vale como está.
     */
    private static String rateLabel(Map<String, Object> raw, Indexer indexer) {
        Object rateRaw = raw.get("rate");
        if (rateRaw instanceof String s && !s.isBlank() && decimal(s) == null) {
            return s.trim();
        }
        BigDecimal rate = decimal(rateRaw);
        BigDecimal fixed = decimal(raw.get("fixedAnnualRate"));
        BigDecimal annual = decimal(raw.get("annualRate"));
        String index = indexer == null || indexer == Indexer.NONE || indexer == Indexer.PREFIXADO
                ? null : indexer.name();

        if (rate != null && index != null) {
            String label = percent(rate) + " " + index;
            if (fixed != null && fixed.signum() != 0) label = index + " + " + percent(fixed);
            return label;
        }
        if (fixed != null && fixed.signum() != 0) {
            return index != null ? index + " + " + percent(fixed) : percent(fixed) + " a.a.";
        }
        if (annual != null && annual.signum() != 0) {
            return percent(annual) + " a.a.";
        }
        if (rate != null && rate.signum() != 0) {
            return percent(rate);
        }
        return null;
    }

    /** "110%" para inteiro, "6,20%" para fracionário — vírgula porque a tela é pt-BR. */
    private static String percent(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        String text = stripped.scale() <= 0
                ? stripped.toPlainString()
                : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        return text + "%";
    }

    static String currency(Object raw) {
        String code = upper(text(raw));
        return code != null && code.matches("[A-Z]{3}") ? code : "BRL";
    }

    static BigDecimal decimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal b) return b;
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return new BigDecimal(n.toString());
        }
        String text = text(raw);
        if (text == null) return null;
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Data ISO do provedor (com ou sem hora); nula se ausente ou ilegível. */
    static LocalDate date(Object raw) {
        String text = text(raw);
        if (text == null || text.length() < 10) return null;
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    static String text(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() || "null".equals(raw) ? null : raw;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /** Maiúsculo e sem acento, para comparar "Pré-fixado" com "PREFIXADO". */
    private static String plain(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toUpperCase(Locale.ROOT);
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
