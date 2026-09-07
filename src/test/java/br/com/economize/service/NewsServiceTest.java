package br.com.economize.service;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.Source;
import br.com.economize.service.news.FeedFetchResult;
import br.com.economize.service.news.NewsProvider;
import br.com.economize.service.news.NewsProviderRegistry;
import br.com.economize.service.news.NewsRefreshScheduler;
import br.com.economize.service.news.NewsRelevanceFilter;
import br.com.economize.service.news.NewsSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * O serviço só filtra o que o {@link NewsSnapshotStore} já tem em memória: o
 * store aqui é real, com dados fixos, e nenhum teste faz I/O.
 */
@ExtendWith(MockitoExtension.class)
class NewsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Mock
    private NewsProviderRegistry registry;

    @Mock
    private NewsRefreshScheduler refresher;

    private NewsSnapshotStore store;
    private NewsService newsService;

    @BeforeEach
    void setUp() {
        store = new NewsSnapshotStore(Duration.ofDays(3), Clock.fixed(NOW, ZoneOffset.UTC));
        newsService = new NewsService(registry, store, refresher, new NewsRelevanceFilter());
    }

    private static NewsArticle article(String title, String url, String publishedAt, String sourceId,
            String... topics) {
        NewsArticle article = new NewsArticle();
        article.setTitle(title);
        article.setDescription("Descrição de " + title);
        article.setUrl(url);
        article.setPublishedAt(publishedAt);
        article.setTopics(List.of(topics));
        Source source = new Source();
        source.setId(sourceId);
        source.setName(sourceId);
        article.setSource(source);
        return article;
    }

    private static NewsProvider stubProvider(String id) {
        return new NewsProvider() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getName() {
                return id;
            }

            @Override
            public String getRegion() {
                return "br";
            }

            @Override
            public String getCategory() {
                return "economia";
            }

            @Override
            public Mono<FeedFetchResult> fetch(String etag, String lastModified) {
                return Mono.just(FeedFetchResult.failed());
            }
        };
    }

    /** Agregado padrão: três artigos de duas fontes, já classificados. */
    private void populateStore() {
        store.update("fonte-a", List.of(
                article("Copom mantém Selic", "https://a.test/1", "2026-08-14T09:00:00-03:00", "fonte-a",
                        "selic-cdi", "macro-br"),
                article("Bitcoin renova máxima", "https://a.test/2", "2026-08-14T08:00:00-03:00", "fonte-a",
                        "cripto")), null, null);
        store.update("fonte-b", List.of(
                article("PETROBRAS anuncia dividendos", "https://b.test/3", "2026-08-13T09:00:00-03:00", "fonte-b",
                        "bolsa")), null, null);
        store.rebuild();
    }

    private static NewsQuery query(String sources, String region, String category, String q, String topics,
            Integer limit) {
        return NewsQuery.of(sources, region, category, q, topics, limit);
    }

    @Test
    @DisplayName("Deve responder do agregado em memória, ordenado por data, sem disparar refresh")
    void shouldServeFromStoreWithoutRefreshing() {
        populateStore();

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, null, null)))
                .assertNext(response -> {
                    assertEquals("ok", response.getStatus());
                    assertEquals(3, response.getTotalResults());
                    assertEquals("Copom mantém Selic", response.getArticles().get(0).getTitle());
                    assertEquals("Bitcoin renova máxima", response.getArticles().get(1).getTitle());
                    assertEquals("PETROBRAS anuncia dividendos", response.getArticles().get(2).getTitle());
                    assertEquals(NOW, response.getUpdatedAt());
                })
                .verifyComplete();

        verifyNoInteractions(refresher);
        // sem filtro de fonte o registry nem é consultado
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("Deve filtrar por tópicos do vocabulário e ignorar ids desconhecidos")
    void shouldFilterByTopics() {
        populateStore();

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, "cripto,bolsa", null)))
                .assertNext(response -> {
                    assertEquals(2, response.getTotalResults());
                    assertEquals("Bitcoin renova máxima", response.getArticles().get(0).getTitle());
                    assertEquals("PETROBRAS anuncia dividendos", response.getArticles().get(1).getTitle());
                })
                .verifyComplete();

        // id desconhecido sozinho não pode virar lista vazia: é ignorado
        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, "nao-existe", null)))
                .assertNext(response -> assertEquals(3, response.getTotalResults()))
                .verifyComplete();

        // misturado com um conhecido, só o conhecido filtra
        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, "nao-existe,selic-cdi", null)))
                .assertNext(response -> {
                    assertEquals(1, response.getTotalResults());
                    assertEquals("Copom mantém Selic", response.getArticles().get(0).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve filtrar por texto (q) em título e descrição, sem case")
    void shouldFilterByTextQuery() {
        populateStore();

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, "petrobras", null, null)))
                .assertNext(response -> {
                    assertEquals(1, response.getTotalResults());
                    assertEquals("PETROBRAS anuncia dividendos", response.getArticles().get(0).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve limitar a quantidade de artigos quando limit for informado")
    void shouldLimitResults() {
        populateStore();

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, null, 2)))
                .assertNext(response -> {
                    assertEquals(2, response.getTotalResults());
                    assertEquals("Copom mantém Selic", response.getArticles().get(0).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Filtros de fonte passam pelo registry e restringem o agregado aos ids selecionados")
    void shouldDelegateSourceSelectionToRegistry() {
        populateStore();
        when(registry.select(any(), any(), any())).thenReturn(List.of(stubProvider("fonte-b")));

        StepVerifier.create(newsService
                .getTopHeadlines(query("fonte-b, fonte-a", "BR", "cripto", null, null, null)))
                .assertNext(response -> {
                    assertEquals(1, response.getTotalResults());
                    assertEquals("fonte-b", response.getArticles().get(0).getSource().getId());
                })
                .verifyComplete();

        verify(registry).select(Set.of("fonte-b", "fonte-a"), "br", "cripto");
    }

    @Test
    @DisplayName("Categoria do contrato antigo (ex.: business) segue aceita e ignorada")
    void legacyCategoryShouldBeIgnored() {
        NewsQuery query = query(null, null, "business", null, null, null);
        assertNull(query.category(), "categoria legada não pode virar filtro");

        NewsQuery blank = query(" ", "", "  ", null, " ", 0);
        assertNull(blank.sources());
        assertNull(blank.region());
        assertNull(blank.category());
        assertNull(blank.topics());
        assertNull(blank.limit());
    }

    @Test
    @DisplayName("Boot frio: dispara o refresh, espera por ele e responde o que ele trouxe")
    void coldStartShouldTriggerRefreshAndWait() {
        when(refresher.refreshNow()).thenReturn(Mono.<Void>fromRunnable(this::populateStore));

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, null, null)))
                .assertNext(response -> {
                    assertEquals(3, response.getTotalResults());
                    assertEquals(NOW, response.getUpdatedAt());
                })
                .verifyComplete();

        verify(refresher).refreshNow();
    }

    @Test
    @DisplayName("Boot frio: se o refresh não terminar em 3 s responde vazio, sem travar a tela")
    void coldStartShouldGiveUpWaitingAfterThreeSeconds() {
        when(refresher.refreshNow()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> newsService.getTopHeadlines(query(null, null, null, null, null, null)))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .thenAwait(NewsService.COLD_START_WAIT)
                .assertNext(response -> {
                    assertEquals("ok", response.getStatus());
                    assertEquals(0, response.getTotalResults());
                    assertNull(response.getUpdatedAt(), "nenhum ciclo terminou: o app precisa saber disso");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Boot frio: erro no refresh não vira erro para o usuário")
    void coldStartShouldSwallowRefreshError() {
        when(refresher.refreshNow()).thenReturn(Mono.error(new IllegalStateException("estouro inesperado")));

        StepVerifier.create(newsService.getTopHeadlines(query(null, null, null, null, null, null)))
                .assertNext(response -> {
                    assertEquals("ok", response.getStatus());
                    assertEquals(0, response.getTotalResults());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /topics deve expor o vocabulário fixo do radar")
    void shouldExposeTopicVocabulary() {
        var topics = newsService.getTopics();

        assertEquals("ok", topics.status());
        assertEquals(13, topics.topics().size());
        assertEquals("selic-cdi", topics.topics().get(0).id());
        assertEquals("Selic e CDI", topics.topics().get(0).label());
    }
}
