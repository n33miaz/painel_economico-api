package br.com.economize.service;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.NewsSourceInfo;
import br.com.economize.dto.NewsSourcesResponse;
import br.com.economize.dto.NewsTopicsResponse;
import br.com.economize.service.news.NewsProvider;
import br.com.economize.service.news.NewsProviderRegistry;
import br.com.economize.service.news.NewsRefreshScheduler;
import br.com.economize.service.news.NewsRelevanceFilter;
import br.com.economize.service.news.NewsSnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class NewsService {

    /**
     * Quanto o boot frio espera pelo primeiro ciclo antes de responder o que
     * houver. Curto de propósito: tela com poucas notícias é melhor que tela
     * travada, e o ciclo continua em segundo plano para a próxima requisição.
     */
    static final Duration COLD_START_WAIT = Duration.ofSeconds(3);

    private final NewsProviderRegistry registry;
    private final NewsSnapshotStore store;
    private final NewsRefreshScheduler refresher;
    private final NewsRelevanceFilter relevanceFilter;

    public NewsService(NewsProviderRegistry registry, NewsSnapshotStore store,
            NewsRefreshScheduler refresher, NewsRelevanceFilter relevanceFilter) {
        this.registry = registry;
        this.store = store;
        this.refresher = refresher;
        this.relevanceFilter = relevanceFilter;
    }

    /**
     * Manchetes filtradas pelo {@link NewsQuery}, lidas do agregado em memória:
     * zero I/O por requisição. Não há mais cache por query — o agregado já é o
     * cache, e uma agregação só, compartilhada por todos os filtros.
     */
    public Mono<NewsResponse> getTopHeadlines(NewsQuery query) {
        if (store.updatedAt() != null) {
            return Mono.fromSupplier(() -> filter(query));
        }
        log.info("Agregado de notícias ainda vazio (boot frio); aguardando até {} s pelo primeiro ciclo",
                COLD_START_WAIT.toSeconds());
        return refresher.refreshNow()
                .timeout(COLD_START_WAIT)
                .onErrorResume(e -> {
                    log.warn("Primeiro ciclo de notícias não terminou a tempo ({}); respondendo o que há",
                            e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .then(Mono.fromSupplier(() -> filter(query)));
    }

    /** Fontes disponíveis para o app montar a configuração de preferências. */
    public NewsSourcesResponse getSources() {
        List<NewsSourceInfo> sources = registry.getAll().stream()
                .map(p -> new NewsSourceInfo(p.getId(), p.getName(), p.getRegion(), p.getCategory()))
                .toList();
        return new NewsSourcesResponse("ok", sources);
    }

    /** Vocabulário de tópicos do radar, para o app montar o filtro. */
    public NewsTopicsResponse getTopics() {
        return new NewsTopicsResponse("ok", relevanceFilter.vocabulary());
    }

    private NewsResponse filter(NewsQuery query) {
        // a seleção de fontes continua sendo do registry (ids, região, categoria);
        // aqui ela vira só o conjunto de ids cujos artigos podem passar
        Set<String> allowedSources = query.hasNoSourceFilter() ? null
                : registry.select(query.sourceIds(), query.region(), query.category()).stream()
                        .map(NewsProvider::getId)
                        .collect(Collectors.toSet());
        Set<String> wantedTopics = relevanceFilter.knownTopics(query.topicIds());

        Stream<NewsArticle> stream = store.aggregate().stream()
                .filter(article -> allowedSources == null || allowedSources.contains(sourceId(article)))
                .filter(article -> wantedTopics == null || matchesAnyTopic(article, wantedTopics))
                .filter(article -> matchesText(article, query.q()));
        if (query.limit() != null) {
            stream = stream.limit(query.limit());
        }
        return toResponse(stream.toList(), store.updatedAt());
    }

    private static NewsResponse toResponse(List<NewsArticle> articles, Instant updatedAt) {
        NewsResponse response = new NewsResponse();
        response.setStatus("ok");
        response.setTotalResults(articles.size());
        response.setUpdatedAt(updatedAt);
        response.setArticles(articles);
        return response;
    }

    private static String sourceId(NewsArticle article) {
        return article.getSource() != null ? article.getSource().getId() : null;
    }

    private static boolean matchesAnyTopic(NewsArticle article, Set<String> wantedTopics) {
        return article.getTopics() != null && article.getTopics().stream().anyMatch(wantedTopics::contains);
    }

    private static boolean matchesText(NewsArticle article, String q) {
        if (q == null) {
            return true;
        }
        return containsIgnoreCase(article.getTitle(), q) || containsIgnoreCase(article.getDescription(), q);
    }

    private static boolean containsIgnoreCase(String text, String lowerCaseTerm) {
        return text != null && text.toLowerCase().contains(lowerCaseTerm);
    }
}
