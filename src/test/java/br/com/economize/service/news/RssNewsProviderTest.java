package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Usa um WebClient real com ExchangeFunction falsa em vez de mocks da cadeia
 * fluente: assim o teste vê os cabeçalhos que o provider realmente manda e
 * exercita o tratamento de status/corpo do próprio WebClient.
 */
class RssNewsProviderTest {

    private static final String VALID_RSS = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Feed Teste</title>
                <item>
                  <title>Mercado sobe hoje</title>
                  <description>&lt;p&gt;Bolsa fecha em alta.&lt;/p&gt;</description>
                  <link>https://example.test/noticia-1</link>
                  <pubDate>Fri, 14 Aug 2026 12:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Dólar cai</title>
                  <description>Moeda recua.</description>
                  <link>https://example.test/noticia-2</link>
                  <pubDate>Fri, 14 Aug 2026 11:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Terceira notícia</title>
                  <description>Excede o limite por feed.</description>
                  <link>https://example.test/noticia-3</link>
                  <pubDate>Fri, 14 Aug 2026 10:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private static final String ATOM_UPDATED_ONLY = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Teste</title>
              <id>urn:teste</id>
              <updated>2026-08-14T12:00:00Z</updated>
              <entry>
                <title>Só tem updated</title>
                <id>urn:teste:1</id>
                <link href="https://example.test/atom-1"/>
                <updated>2026-08-14T09:30:00Z</updated>
              </entry>
            </feed>
            """;

    private final AtomicReference<ClientRequest> lastRequest = new AtomicReference<>();

    private RssNewsProvider provider(Function<ClientRequest, Mono<ClientResponse>> exchange) {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    lastRequest.set(request);
                    return exchange.apply(request);
                })
                .build();
        NewsFeedsProperties.Feed feed = NewsFeedsProperties.Feed.of(
                "feed-teste", "Feed Teste", "https://example.test/rss", "br", "economia");
        return new RssNewsProvider(feed, client, Duration.ofMillis(300), 2);
    }

    private static Mono<ClientResponse> ok(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/rss+xml; charset=UTF-8")
                .header(HttpHeaders.ETAG, "\"v1\"")
                .header(HttpHeaders.LAST_MODIFIED, "Fri, 14 Aug 2026 12:00:00 GMT")
                .body(body)
                .build());
    }

    @Test
    @DisplayName("Deve parsear o RSS, preencher a fonte, respeitar o limite e guardar os validadores")
    void shouldParseRssAndApplyItemLimit() {
        RssNewsProvider provider = provider(request -> ok(VALID_RSS));

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> {
                    assertEquals(FeedFetchResult.Status.UPDATED, result.status());
                    assertEquals(2, result.articles().size(), "itemsPerFeed=2 deve cortar o terceiro item");
                    NewsArticle first = result.articles().get(0);
                    assertEquals("Mercado sobe hoje", first.getTitle());
                    assertEquals("Bolsa fecha em alta.", first.getDescription());
                    assertEquals("https://example.test/noticia-1", first.getUrl());
                    assertEquals("feed-teste", first.getSource().getId());
                    assertEquals("Feed Teste", first.getSource().getName());
                    assertNotNull(first.getPublishedAt());
                    // validadores voltam para o store fazer o próximo GET condicional
                    assertEquals("\"v1\"", result.etag());
                    assertEquals("Fri, 14 Aug 2026 12:00:00 GMT", result.lastModified());
                })
                .verifyComplete();

        // sem validadores anteriores, o GET é incondicional
        HttpHeaders sent = lastRequest.get().headers();
        assertNull(sent.getFirst(HttpHeaders.IF_NONE_MATCH));
        assertNull(sent.getFirst(HttpHeaders.IF_MODIFIED_SINCE));
    }

    @Test
    @DisplayName("Com validadores anteriores deve mandar If-None-Match e If-Modified-Since")
    void shouldSendConditionalHeaders() {
        RssNewsProvider provider = provider(request -> ok(VALID_RSS));

        StepVerifier.create(provider.fetch("\"antigo\"", "Thu, 13 Aug 2026 10:00:00 GMT"))
                .expectNextCount(1)
                .verifyComplete();

        HttpHeaders sent = lastRequest.get().headers();
        assertEquals("\"antigo\"", sent.getFirst(HttpHeaders.IF_NONE_MATCH));
        assertEquals("Thu, 13 Aug 2026 10:00:00 GMT", sent.getFirst(HttpHeaders.IF_MODIFIED_SINCE));
    }

    @Test
    @DisplayName("304 Not Modified deve virar UNCHANGED sem tentar parse")
    void shouldReturnUnchangedOn304() {
        RssNewsProvider provider = provider(
                request -> Mono.just(ClientResponse.create(HttpStatus.NOT_MODIFIED).build()));

        StepVerifier.create(provider.fetch("\"v1\"", null))
                .assertNext(result -> {
                    assertEquals(FeedFetchResult.Status.UNCHANGED, result.status());
                    assertTrue(result.articles().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Erro HTTP do feed deve virar FAILED, sem propagar")
    void shouldReturnFailedOnHttpError() {
        RssNewsProvider provider = provider(
                request -> Mono.just(ClientResponse.create(HttpStatus.GONE).build()));

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> assertEquals(FeedFetchResult.Status.FAILED, result.status()))
                .verifyComplete();
    }

    @Test
    @DisplayName("XML inválido deve virar FAILED, e não uma lista vazia que apagaria a anterior")
    void shouldReturnFailedOnInvalidXml() {
        RssNewsProvider provider = provider(request -> ok("<html>não sou RSS</html>"));

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> assertEquals(FeedFetchResult.Status.FAILED, result.status()))
                .verifyComplete();
    }

    @Test
    @DisplayName("200 sem corpo é fonte quebrada: FAILED")
    void shouldReturnFailedOnEmptyBody() {
        RssNewsProvider provider = provider(request -> ok(""));

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> assertEquals(FeedFetchResult.Status.FAILED, result.status()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Feed lento deve estourar o timeout e virar FAILED")
    void shouldTimeoutSlowFeed() {
        RssNewsProvider provider = provider(request -> Mono.never());

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> assertEquals(FeedFetchResult.Status.FAILED, result.status()))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Entrada Atom só com <updated> deve usar essa data como publicação")
    void shouldFallbackToUpdatedDate() {
        RssNewsProvider provider = provider(request -> ok(ATOM_UPDATED_ONLY));

        StepVerifier.create(provider.fetch(null, null))
                .assertNext(result -> {
                    assertEquals(1, result.articles().size());
                    assertNotNull(result.articles().get(0).getPublishedAt(),
                            "sem a alternativa o artigo ficaria sem data e cairia para o fim da lista");
                })
                .verifyComplete();
    }
}
