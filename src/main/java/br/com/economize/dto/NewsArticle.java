package br.com.economize.dto;

import lombok.Data;

import java.util.List;

@Data
public class NewsArticle {
    private Source source;
    private String author;
    private String title;
    private String description;
    private String url;
    private String urlToImage;
    private String publishedAt;
    private String content;
    /**
     * Categoria editorial do feed de origem (economia, mercados, investimentos,
     * cripto, geral). É ela que decide quão exigente o radar de relevância é com
     * o artigo: feed "geral" precisa provar que fala de dinheiro.
     */
    private String sourceCategory;
    /**
     * Tópicos do vocabulário fixo do radar (ver NewsRelevanceFilter) que o
     * artigo casou. Vazia quando nenhum casou — só acontece em feed financeiro,
     * porque em feed geral o artigo sem tópico nem entra.
     */
    private List<String> topics = List.of();
}
