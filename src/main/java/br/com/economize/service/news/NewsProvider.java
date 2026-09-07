package br.com.economize.service.news;

import reactor.core.publisher.Mono;

/**
 * Fonte de notícias plugável. O agregado não conhece RSS nem HTTP: qualquer
 * implementação (RSS, API proprietária...) entra no registry pelos metadados.
 */
public interface NewsProvider {

    /** Identificador estável usado no filtro ?sources= e nas preferências do app. */
    String getId();

    String getName();

    /** Região da fonte: "br" ou "global". */
    String getRegion();

    /** Categoria editorial: economia, mercados, investimentos, cripto, geral... */
    String getCategory();

    /**
     * Busca os artigos mais recentes da fonte. Recebe os validadores HTTP da
     * última resposta boa (ETag e Last-Modified, qualquer um pode ser null) para
     * fazer GET condicional: se a fonte responder que nada mudou, o resultado é
     * UNCHANGED e ninguém gasta CPU parseando XML de novo.
     *
     * <p>Nunca deve propagar erro: falha de rede, timeout ou XML inválido viram
     * {@link FeedFetchResult#failed()}, e quem chama preserva a última lista boa.
     */
    Mono<FeedFetchResult> fetch(String etag, String lastModified);
}
