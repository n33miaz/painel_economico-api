package br.com.economize.dto;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtros normalizados do endpoint de manchetes. A normalização
 * (trim/minúsculas/brancos viram null) existe para que requisições equivalentes
 * sejam tratadas de forma idêntica, independentemente de como o app as montou.
 */
public record NewsQuery(String sources, String region, String category, String q, String topics, Integer limit) {

    /**
     * Valores de categoria do contrato antigo (estilo NewsAPI) que sempre foram
     * aceitos e ignorados. Continuam sem efeito: o APK publicado envia
     * "business" por padrão e não pode passar a receber resposta filtrada/vazia.
     */
    private static final Set<String> LEGACY_IGNORED_CATEGORIES = Set.of(
            "business", "entertainment", "general", "health", "science", "sports", "technology");

    public static NewsQuery of(String sources, String region, String category, String q, String topics,
            Integer limit) {
        String normalizedCategory = normalize(category);
        if (normalizedCategory != null && LEGACY_IGNORED_CATEGORIES.contains(normalizedCategory)) {
            normalizedCategory = null;
        }
        Integer normalizedLimit = (limit != null && limit > 0) ? limit : null;
        return new NewsQuery(normalize(sources), normalize(region), normalizedCategory,
                normalize(q), normalize(topics), normalizedLimit);
    }

    /** IDs de fontes do filtro ?sources=, ou null quando não há filtro. */
    public Set<String> sourceIds() {
        return splitCsv(sources);
    }

    /**
     * IDs de tópicos do filtro ?topics=, ou null quando não há filtro. Aqui só
     * se separa a lista; quem sabe quais ids existem é o NewsRelevanceFilter, e
     * é lá que os desconhecidos são descartados.
     */
    public Set<String> topicIds() {
        return splitCsv(topics);
    }

    /** True quando nenhum filtro de FONTE foi pedido (sources/region/category). */
    public boolean hasNoSourceFilter() {
        return sources == null && region == null && category == null;
    }

    private static Set<String> splitCsv(String csv) {
        if (csv == null) {
            return null;
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
