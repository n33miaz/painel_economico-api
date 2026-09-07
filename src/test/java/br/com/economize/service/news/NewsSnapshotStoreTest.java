package br.com.economize.service.news;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsSnapshotStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    /** Relógio que o teste avança na mão, para observar fetchedAt/updatedAt. */
    private static final class TestClock extends Clock {
        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final TestClock clock = new TestClock();
    private final NewsSnapshotStore store = new NewsSnapshotStore(Duration.ofDays(3), clock);

    private static NewsArticle article(String title, String url, Instant publishedAt) {
        NewsArticle article = new NewsArticle();
        article.setTitle(title);
        article.setUrl(url);
        article.setPublishedAt(publishedAt != null ? publishedAt.atOffset(ZoneOffset.UTC).toString() : null);
        Source source = new Source();
        source.setId("fonte");
        source.setName("Fonte");
        article.setSource(source);
        return article;
    }

    private static List<String> titles(List<NewsArticle> articles) {
        return articles.stream().map(NewsArticle::getTitle).toList();
    }

    @Test
    @DisplayName("Feed desconhecido devolve snapshot vazio, sem validadores")
    void unknownFeedIsEmpty() {
        NewsSnapshotStore.FeedSnapshot snapshot = store.feed("nunca-vi");

        assertTrue(snapshot.articles().isEmpty());
        assertNull(snapshot.etag());
        assertNull(snapshot.lastModified());
        assertNull(snapshot.fetchedAt());
        assertNull(store.updatedAt(), "sem nenhum feed respondido, o agregado é frio");
        assertTrue(store.aggregate().isEmpty());
    }

    @Test
    @DisplayName("rebuild ordena por data decrescente e marca updatedAt")
    void rebuildSortsByDateDesc() {
        store.update("a", List.of(
                article("Antiga", "https://a/1", NOW.minus(Duration.ofHours(30))),
                article("Sem data", "https://a/2", null)), "\"e-a\"", "lm-a");
        store.update("b", List.of(
                article("Recente", "https://b/1", NOW.minus(Duration.ofHours(1)))), null, null);

        store.rebuild();

        assertEquals(List.of("Recente", "Antiga", "Sem data"), titles(store.aggregate()));
        assertEquals(NOW, store.updatedAt());
        assertEquals("\"e-a\"", store.feed("a").etag());
        assertEquals("lm-a", store.feed("a").lastModified());
    }

    @Test
    @DisplayName("Feed que falha (não é atualizado) preserva a lista anterior no agregado")
    void failedFeedKeepsPreviousList() {
        store.update("a", List.of(article("Da fonte A", "https://a/1", NOW.minus(Duration.ofHours(2)))),
                "\"e1\"", null);
        store.update("b", List.of(article("Da fonte B", "https://b/1", NOW.minus(Duration.ofHours(1)))), null,
                null);
        store.rebuild();

        // ciclo seguinte: só B respondeu; A falhou e ninguém chamou update/markUnchanged para ela
        clock.advance(Duration.ofMinutes(10));
        store.update("b", List.of(article("Nova da B", "https://b/2", NOW.minus(Duration.ofMinutes(5)))), null,
                null);
        store.rebuild();

        assertEquals(List.of("Nova da B", "Da fonte A"), titles(store.aggregate()));
        assertEquals("\"e1\"", store.feed("a").etag(), "validadores da A seguem para o próximo GET condicional");
        assertEquals(NOW.plus(Duration.ofMinutes(10)), store.updatedAt());
    }

    @Test
    @DisplayName("304 mantém lista e validadores, só avança o instante da confirmação")
    void unchangedKeepsListAndValidators() {
        store.update("a", List.of(article("Da fonte A", "https://a/1", NOW.minus(Duration.ofHours(2)))),
                "\"e1\"", "lm1");
        store.rebuild();

        clock.advance(Duration.ofMinutes(10));
        store.markUnchanged("a");
        store.rebuild();

        assertEquals(List.of("Da fonte A"), titles(store.aggregate()));
        assertEquals("\"e1\"", store.feed("a").etag());
        assertEquals("lm1", store.feed("a").lastModified());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), store.feed("a").fetchedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), store.updatedAt());
    }

    @Test
    @DisplayName("304 para feed que nunca respondeu não cria snapshot")
    void unchangedForUnknownFeedIsNoop() {
        store.markUnchanged("fantasma");
        store.rebuild();

        assertNull(store.feed("fantasma").fetchedAt());
        assertNull(store.updatedAt());
    }

    @Test
    @DisplayName("Artigos com mais de max-age saem do agregado; sem data ficam")
    void rebuildDropsOldArticles() {
        store.update("a", List.of(
                article("Quatro dias", "https://a/1", NOW.minus(Duration.ofDays(4))),
                article("Dois dias", "https://a/2", NOW.minus(Duration.ofDays(2))),
                article("Sem data", "https://a/3", null)), null, null);

        store.rebuild();

        assertEquals(List.of("Dois dias", "Sem data"), titles(store.aggregate()));
        // a lista por feed segue íntegra: o corte é do agregado, não da memória da fonte
        assertEquals(3, store.feed("a").articles().size());
    }

    @Test
    @DisplayName("Mesmo link em duas fontes entra uma vez, na versão mais recente")
    void rebuildDeduplicatesByUrl() {
        String shared = "https://agencia.test/materia";
        store.update("a", List.of(article("Original", shared, NOW.minus(Duration.ofHours(3)))), null, null);
        store.update("b", List.of(article("Replicada", shared, NOW.minus(Duration.ofHours(1)))), null, null);

        store.rebuild();

        assertEquals(List.of("Replicada"), titles(store.aggregate()));
    }

    @Test
    @DisplayName("Sem link, a chave de dedupe é fonte + título")
    void rebuildDeduplicatesByTitleWhenUrlMissing() {
        store.update("a", List.of(
                article("Mesmo título", null, NOW.minus(Duration.ofHours(2))),
                article("Mesmo título", "", NOW.minus(Duration.ofHours(1)))), null, null);

        store.rebuild();

        assertEquals(1, store.aggregate().size());
    }

    @Test
    @DisplayName("update substitui a lista do feed por inteiro")
    void updateReplacesFeedList() {
        store.update("a", List.of(article("Velha", "https://a/1", NOW.minus(Duration.ofHours(2)))), null, null);
        store.update("a", List.of(article("Nova", "https://a/2", NOW.minus(Duration.ofHours(1)))), null, null);

        store.rebuild();

        assertEquals(List.of("Nova"), titles(store.aggregate()));
    }
}
