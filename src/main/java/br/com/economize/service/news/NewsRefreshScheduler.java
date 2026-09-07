package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Baixa os feeds em segundo plano e alimenta o {@link NewsSnapshotStore}. Existe
 * porque no plano free (0,1 CPU) onze downloads TLS simultâneos de até 650 KB
 * não cabem em 10 s: todos estouravam o timeout no mesmo segundo, o usuário
 * esperava 10 s por uma tela vazia e o cache guardava o vazio por 10 min.
 *
 * <p>Aqui a requisição do usuário nunca espera por rede. Os feeds são buscados
 * poucos por vez ({@code economize.news.concurrency}), com GET condicional, e
 * cada um que falha só deixa de atualizar a própria lista — o resto do agregado
 * continua servindo.
 */
@Slf4j
@Component
public class NewsRefreshScheduler {

    private final NewsProviderRegistry registry;
    private final NewsSnapshotStore store;
    private final NewsRelevanceFilter relevanceFilter;
    private final int concurrency;
    private final Duration feedTimeout;

    /** Ciclo em andamento, para quem chegar no meio se juntar a ele em vez de disparar outro. */
    private Mono<Void> inFlight;

    public NewsRefreshScheduler(NewsProviderRegistry registry, NewsSnapshotStore store,
            NewsRelevanceFilter relevanceFilter, NewsFeedsProperties properties) {
        this.registry = registry;
        this.store = store;
        this.relevanceFilter = relevanceFilter;
        this.concurrency = Math.max(1, properties.getConcurrency());
        this.feedTimeout = properties.getFeedTimeout();
    }

    /**
     * Ciclo periódico. Bloqueia a thread do agendador de propósito: assim o
     * {@code fixedDelay} conta a partir do FIM do ciclo e dois ciclos nunca se
     * sobrepõem. O bloqueio é limitado porque cada feed tem timeout próprio.
     */
    @Scheduled(fixedDelayString = "${economize.news.refresh-interval:PT10M}", initialDelayString = "PT5S")
    public void scheduledRefresh() {
        try {
            refreshNow().block();
        } catch (RuntimeException e) {
            log.error("Ciclo de atualização de notícias abortado: {}", e.getMessage());
        }
    }

    /**
     * Dispara um ciclo (ou devolve o que já está rodando). O Mono completa quando
     * o agregado foi reconstruído; quem espera pode desistir antes (timeout) sem
     * interromper o ciclo, que continua em segundo plano.
     */
    public Mono<Void> refreshNow() {
        Mono<Void> run;
        boolean starter = false;
        synchronized (this) {
            if (inFlight == null) {
                inFlight = refreshAll()
                        .doFinally(signal -> clearInFlight())
                        .cache();
                starter = true;
            }
            run = inFlight;
        }
        if (starter) {
            // assinatura própria: o ciclo roda até o fim mesmo que quem pediu
            // (o boot frio do NewsService, p.ex.) cancele a dele por timeout
            run.subscribe(unused -> {
            }, e -> log.error("Falha inesperada na atualização de notícias: {}", e.getMessage()));
        }
        return run;
    }

    private synchronized void clearInFlight() {
        inFlight = null;
    }

    private Mono<Void> refreshAll() {
        List<NewsProvider> providers = registry.getAll();
        return Flux.fromIterable(providers)
                .flatMap(this::refreshFeed, concurrency)
                .then(Mono.<Void>fromRunnable(store::rebuild))
                .doOnSubscribe(s -> log.info("Atualizando {} feeds de notícias, {} por vez", providers.size(),
                        concurrency))
                .doOnSuccess(v -> log.info("Radar de notícias atualizado: {} artigos no agregado",
                        store.aggregate().size()));
    }

    private Mono<Void> refreshFeed(NewsProvider provider) {
        NewsSnapshotStore.FeedSnapshot previous = store.feed(provider.getId());
        return provider.fetch(previous.etag(), previous.lastModified())
                // rede de segurança: o provider já tem timeout próprio, mas uma
                // implementação que o ignore não pode segurar o ciclo inteiro
                .timeout(feedTimeout.plusSeconds(2), Mono.fromCallable(() -> {
                    log.warn("Fonte {} não respondeu dentro do ciclo; mantendo a lista anterior", provider.getId());
                    return FeedFetchResult.failed();
                }))
                .doOnNext(result -> apply(provider, result))
                .then();
    }

    private void apply(NewsProvider provider, FeedFetchResult result) {
        switch (result.status()) {
            case UPDATED -> {
                // a categoria vem do catálogo de feeds, não do XML: é ela que
                // regula a exigência do radar sobre cada artigo
                result.articles().forEach(article -> article.setSourceCategory(provider.getCategory()));
                List<NewsArticle> relevant = relevanceFilter.filter(result.articles());
                store.update(provider.getId(), relevant, result.etag(), result.lastModified());
                log.debug("Fonte {}: {} artigos, {} relevantes", provider.getId(), result.articles().size(),
                        relevant.size());
            }
            case UNCHANGED -> {
                store.markUnchanged(provider.getId());
                log.debug("Fonte {}: sem mudanças (304)", provider.getId());
            }
            case FAILED -> log.debug("Fonte {}: falha; mantendo os {} artigos da última busca boa",
                    provider.getId(), store.feed(provider.getId()).articles().size());
        }
    }
}
