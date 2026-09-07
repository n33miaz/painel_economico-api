package br.com.economize.service.news;

import br.com.economize.dto.NewsArticle;

import java.util.List;

/**
 * Resultado de uma busca em um feed. Separa três destinos que o agregado trata
 * de forma diferente: lista nova (substitui a anterior), "nada mudou" (mantém a
 * anterior sem gastar parse) e falha (mantém a anterior e tenta de novo depois).
 *
 * @param etag         validador devolvido pela fonte, para o próximo GET condicional
 * @param lastModified idem, no formato bruto da fonte — vai de volta sem reparse
 */
public record FeedFetchResult(Status status, List<NewsArticle> articles, String etag, String lastModified) {

    public enum Status {
        UPDATED, UNCHANGED, FAILED
    }

    public static FeedFetchResult updated(List<NewsArticle> articles, String etag, String lastModified) {
        return new FeedFetchResult(Status.UPDATED, List.copyOf(articles), etag, lastModified);
    }

    public static FeedFetchResult unchanged() {
        return new FeedFetchResult(Status.UNCHANGED, List.of(), null, null);
    }

    public static FeedFetchResult failed() {
        return new FeedFetchResult(Status.FAILED, List.of(), null, null);
    }
}
