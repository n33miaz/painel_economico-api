package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.Source;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.StringReader;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * Implementação genérica de {@link NewsProvider} para qualquer feed RSS/Atom,
 * parametrizada pelos metadados vindos de {@link NewsFeedsProperties}.
 */
@Slf4j
public class RssNewsProvider implements NewsProvider {

    private final NewsFeedsProperties.Feed feed;
    private final WebClient webClient;
    private final Duration timeout;
    private final int itemsPerFeed;

    public RssNewsProvider(NewsFeedsProperties.Feed feed, WebClient webClient,
            Duration timeout, int itemsPerFeed) {
        this.feed = feed;
        this.webClient = webClient;
        this.timeout = timeout;
        this.itemsPerFeed = itemsPerFeed;
    }

    @Override
    public String getId() {
        return feed.getId();
    }

    @Override
    public String getName() {
        return feed.getName();
    }

    @Override
    public String getRegion() {
        return feed.getRegion();
    }

    @Override
    public String getCategory() {
        return feed.getCategory();
    }

    @Override
    public Mono<FeedFetchResult> fetch(String etag, String lastModified) {
        return webClient.get()
                .uri(feed.getUrl())
                .headers(headers -> {
                    // GET condicional: com os validadores da última resposta boa a
                    // fonte responde 304 sem corpo, e o parse — a parte cara numa CPU
                    // de 0,1 — é pulado. O Last-Modified volta no formato bruto em
                    // que veio, sem reparse de data
                    if (etag != null) {
                        headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                    }
                    if (lastModified != null) {
                        headers.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
                    }
                })
                .retrieve()
                .toEntity(String.class)
                .timeout(timeout)
                // parse do XML é bloqueante; sai do event loop do Netty
                .publishOn(Schedulers.boundedElastic())
                .map(this::toResult)
                .onErrorResume(e -> {
                    // um feed fora do ar nunca derruba o agregado: quem chama
                    // mantém a última lista boa desta fonte e tenta no próximo ciclo
                    log.warn("Erro ao buscar RSS da fonte {}: {}", feed.getId(), e.getMessage());
                    return Mono.just(FeedFetchResult.failed());
                });
    }

    private FeedFetchResult toResult(ResponseEntity<String> response) {
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return FeedFetchResult.unchanged();
        }
        String xml = response.getBody();
        if (xml == null || xml.isBlank()) {
            // 200 sem corpo é uma fonte quebrada, não uma fonte sem notícias:
            // tratar como falha preserva a lista anterior em vez de zerá-la
            throw new IllegalStateException("resposta sem corpo");
        }
        return FeedFetchResult.updated(parseRss(xml),
                response.getHeaders().getETag(),
                response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED));
    }

    private List<NewsArticle> parseRss(String xml) {
        try {
            SyndFeed syndFeed = new SyndFeedInput().build(new StringReader(xml));
            return syndFeed.getEntries().stream()
                    .limit(itemsPerFeed)
                    .map(this::mapToNewsArticle)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("XML inválido: " + e.getMessage(), e);
        }
    }

    private NewsArticle mapToNewsArticle(SyndEntry entry) {
        NewsArticle article = new NewsArticle();
        article.setTitle(entry.getTitle());

        // Limpa tags HTML da descrição
        String description = entry.getDescription() != null
                ? entry.getDescription().getValue().replaceAll("<[^>]*>", "").trim()
                : "";
        // Limita o tamanho da descrição
        if (description.length() > 150) {
            description = description.substring(0, 147) + "...";
        }
        article.setDescription(description);

        article.setUrl(entry.getLink());
        article.setAuthor(entry.getAuthor());

        // feeds Atom só trazem <updated>; sem esta alternativa o artigo ficaria
        // sem data e cairia para o fim da lista
        Date published = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        if (published != null) {
            article.setPublishedAt(published.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }

        Source source = new Source();
        source.setId(feed.getId());
        source.setName(feed.getName());
        article.setSource(source);

        if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
            article.setUrlToImage(entry.getEnclosures().get(0).getUrl());
        }

        return article;
    }
}
