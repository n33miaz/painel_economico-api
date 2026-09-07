package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsRefreshSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    /** Provider de teste que registra como foi chamado e responde o que o teste mandar. */
    private static final class StubProvider implements NewsProvider {
        private final String id;
        private final String category;
        private final Supplier<Mono<FeedFetchResult>> response;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> etagsSeen = new ArrayList<>();
        final List<String> lastModifiedSeen = new ArrayList<>();

        StubProvider(String id, String category, Supplier<Mono<FeedFetchResult>> response) {
            this.id = id;
            this.category = category;
            this.response = response;
        }

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
            return category;
        }

        @Override
        public Mono<FeedFetchResult> fetch(String etag, String lastModified) {
            calls.incrementAndGet();
            etagsSeen.add(etag);
            lastModifiedSeen.add(lastModified);
            return response.get();
        }
    }

    @Mock
    private NewsProviderRegistry registry;

    private NewsFeedsProperties properties;
    private NewsSnapshotStore store;
    private NewsRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new NewsFeedsProperties();
        store = new NewsSnapshotStore(Duration.ofDays(3), Clock.fixed(NOW, ZoneOffset.UTC));
        scheduler = new NewsRefreshScheduler(registry, store, new NewsRelevanceFilter(), properties);
    }

    private static NewsArticle article(String sourceId, String title, String url) {
        NewsArticle article = new NewsArticle();
        article.setTitle(title);
        article.setDescription("");
        article.setUrl(url);
        article.setPublishedAt(NOW.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC).toString());
        Source source = new Source();
        source.setId(sourceId);
        source.setName(sourceId);
        article.setSource(source);
        return article;
    }

    private static Mono<FeedFetchResult> updated(String etag, String lastModified, NewsArticle... articles) {
        return Mono.just(FeedFetchResult.updated(List.of(articles), etag, lastModified));
    }

    @Test
    @DisplayName("Ciclo busca os feeds, classifica, carimba a categoria da fonte e reconstrói o agregado")
    void cycleFeedsTheStore() {
        StubProvider a = new StubProvider("a", "economia", () -> updated("\"ea\"", "lm-a",
                article("a", "Copom mantém Selic", "https://a/1")));
        StubProvider b = new StubProvider("b", "cripto", () -> updated(null, null,
                article("b", "Bitcoin renova máxima", "https://b/1")));
        when(registry.getAll()).thenReturn(List.of(a, b));

        StepVerifier.create(scheduler.refreshNow()).verifyComplete();

        assertEquals(2, store.aggregate().size());
        assertEquals(NOW, store.updatedAt());
        assertEquals("\"ea\"", store.feed("a").etag());
        assertEquals("lm-a", store.feed("a").lastModified());
        NewsArticle selic = store.feed("a").articles().get(0);
        assertEquals("economia", selic.getSourceCategory());
        assertTrue(selic.getTopics().contains("selic-cdi"));
        assertEquals(List.of("cripto"), store.feed("b").articles().get(0).getTopics());
        // primeira busca é incondicional
        assertEquals(1, a.etagsSeen.size());
        assertNull(a.etagsSeen.get(0));
        assertNull(a.lastModifiedSeen.get(0));
    }

    @Test
    @DisplayName("Ciclo aplica o radar: futebol de feed geral não chega ao store")
    void cycleAppliesRelevanceFilter() {
        StubProvider geral = new StubProvider("portal", "geral", () -> updated(null, null,
                article("portal", "Flamengo vence o Palmeiras no Maracanã", "https://p/1"),
                article("portal", "Copom mantém Selic em 15%", "https://p/2")));
        when(registry.getAll()).thenReturn(List.of(geral));

        StepVerifier.create(scheduler.refreshNow()).verifyComplete();

        assertEquals(1, store.aggregate().size());
        assertEquals("Copom mantém Selic em 15%", store.aggregate().get(0).getTitle());
    }

    @Test
    @DisplayName("Feed que falha mantém a última lista boa e manda os validadores anteriores")
    void failedFeedKeepsPreviousSnapshot() {
        store.update("a", List.of(article("a", "Da última vez", "https://a/old")), "\"e1\"", "lm1");
        store.rebuild();
        StubProvider a = new StubProvider("a", "economia", () -> Mono.just(FeedFetchResult.failed()));
        StubProvider b = new StubProvider("b", "economia", () -> updated(null, null,
                article("b", "Nova da B", "https://b/1")));
        when(registry.getAll()).thenReturn(List.of(a, b));

        StepVerifier.create(scheduler.refreshNow()).verifyComplete();

        assertEquals(List.of("\"e1\""), a.etagsSeen);
        assertEquals(List.of("lm1"), a.lastModifiedSeen);
        assertEquals(1, store.feed("a").articles().size());
        assertEquals("Da última vez", store.feed("a").articles().get(0).getTitle());
        assertEquals(2, store.aggregate().size(), "a falha de A não apaga A do agregado");
    }

    @Test
    @DisplayName("304 mantém a lista anterior sem reclassificar nada")
    void unchangedFeedKeepsPreviousSnapshot() {
        store.update("a", List.of(article("a", "Da última vez", "https://a/old")), "\"e1\"", null);
        store.rebuild();
        StubProvider a = new StubProvider("a", "economia", () -> Mono.just(FeedFetchResult.unchanged()));
        when(registry.getAll()).thenReturn(List.of(a));

        StepVerifier.create(scheduler.refreshNow()).verifyComplete();

        assertEquals("Da última vez", store.feed("a").articles().get(0).getTitle());
        assertEquals("\"e1\"", store.feed("a").etag());
        assertEquals(1, store.aggregate().size());
    }

    @Test
    @DisplayName("Quem chama refreshNow durante um ciclo se junta a ele; depois do fim começa outro")
    void refreshNowJoinsInFlightCycle() {
        Sinks.One<FeedFetchResult> gate = Sinks.one();
        StubProvider a = new StubProvider("a", "economia", gate::asMono);
        when(registry.getAll()).thenReturn(List.of(a));

        Mono<Void> first = scheduler.refreshNow();
        Mono<Void> second = scheduler.refreshNow();

        assertSame(first, second, "segundo pedido no meio do ciclo não dispara outro download");
        assertEquals(1, a.calls.get());

        gate.tryEmitValue(FeedFetchResult.updated(List.of(article("a", "Um", "https://a/1")), null, null));
        StepVerifier.create(second).verifyComplete();
        assertEquals(1, store.aggregate().size());

        // ciclo encerrado: o próximo pedido busca de novo
        StepVerifier.create(scheduler.refreshNow()).verifyComplete();
        assertEquals(2, a.calls.get());
    }

    @Test
    @DisplayName("Provider que ignora o próprio timeout não segura o ciclo: rede de segurança vira falha")
    void safetyTimeoutTreatsHungProviderAsFailure() {
        properties.setFeedTimeout(Duration.ofSeconds(1));
        scheduler = new NewsRefreshScheduler(registry, store, new NewsRelevanceFilter(), properties);
        store.update("travado", List.of(article("travado", "Antiga", "https://t/1")), null, null);
        store.rebuild();
        StubProvider hung = new StubProvider("travado", "economia", Mono::never);
        StubProvider ok = new StubProvider("ok", "economia", () -> updated(null, null,
                article("ok", "Nova", "https://ok/1")));
        when(registry.getAll()).thenReturn(List.of(hung, ok));

        StepVerifier.withVirtualTime(() -> scheduler.refreshNow())
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(4))
                .verifyComplete();

        assertEquals(2, store.aggregate().size());
        assertEquals("Antiga", store.feed("travado").articles().get(0).getTitle());
    }

    @Test
    @DisplayName("scheduledRefresh roda o ciclo inteiro antes de devolver a thread do agendador")
    void scheduledRefreshBlocksUntilDone() {
        StubProvider a = new StubProvider("a", "economia", () -> updated(null, null,
                article("a", "Um", "https://a/1")));
        when(registry.getAll()).thenReturn(List.of(a));

        scheduler.scheduledRefresh();

        assertEquals(1, store.aggregate().size());
        assertNotNull(store.updatedAt());
    }

    @Test
    @DisplayName("Sem nenhum feed respondendo, o agregado continua frio")
    void allFailingKeepsStoreCold() {
        StubProvider a = new StubProvider("a", "economia", () -> Mono.just(FeedFetchResult.failed()));
        when(registry.getAll()).thenReturn(List.of(a));

        StepVerifier.create(scheduler.refreshNow()).verifyComplete();

        assertNull(store.updatedAt());
        assertTrue(store.aggregate().isEmpty());
    }
}
