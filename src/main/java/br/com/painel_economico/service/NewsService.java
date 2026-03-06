package br.com.painel_economico.service;

import br.com.painel_economico.dto.NewsArticle;
import br.com.painel_economico.dto.NewsResponse;
import br.com.painel_economico.dto.Source;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.StringReader;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NewsService {

        private final WebClient webClient;

        // Fontes de notícias financeiras via RSS
        private static final Map<String, String> RSS_FEEDS = Map.of(
                        "InfoMoney", "https://www.infomoney.com.br/feed/",
                        "CoinTelegraph", "https://br.cointelegraph.com/rss_feed",
                        "InvestNews", "https://investnews.com.br/feed/");

        public NewsService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("news")
        public Mono<NewsResponse> getTopHeadlines(String country, String category) {
                log.info("Iniciando agregacao de noticias via RSS Feeds...");

                return Flux.fromIterable(RSS_FEEDS.entrySet())
                                .flatMap(entry -> fetchAndParseRss(entry.getKey(), entry.getValue()))
                                .sort(Comparator.comparing(NewsArticle::getPublishedAt).reversed())
                                .collectList()
                                .map(articles -> {
                                        NewsResponse response = new NewsResponse();
                                        response.setStatus("ok");
                                        response.setTotalResults(articles.size());
                                        response.setArticles(articles);
                                        return response;
                                })
                                .onErrorResume(e -> {
                                        log.error("Falha catastrófica ao buscar notícias: {}", e.getMessage());
                                        return Mono.just(new NewsResponse()); // Retorna vazio em caso de falha total
                                });
        }

        @SuppressWarnings("null")
        private Flux<NewsArticle> fetchAndParseRss(String sourceName, String url) {
                return webClient.get()
                                .uri(url)
                                .retrieve()
                                .bodyToMono(String.class)
                                .publishOn(Schedulers.boundedElastic())
                                .flatMapMany(xml -> {
                                        try {
                                                SyndFeedInput input = new SyndFeedInput();
                                                SyndFeed feed = input.build(new StringReader(xml));

                                                List<NewsArticle> articles = feed.getEntries().stream()
                                                                .limit(10) // Pega as 10 últimas de cada fonte
                                                                .map(entry -> mapToNewsArticle(sourceName, entry))
                                                                .toList();

                                                return Flux.fromIterable(articles);
                                        } catch (Exception e) {
                                                log.warn("Erro ao fazer parse do RSS da fonte {}: {}", sourceName,
                                                                e.getMessage());
                                                return Flux.empty();
                                        }
                                })
                                .onErrorResume(e -> {
                                        log.warn("Erro ao buscar RSS da fonte {}: {}", sourceName, e.getMessage());
                                        return Flux.empty();
                                });
        }

        private NewsArticle mapToNewsArticle(String sourceName, SyndEntry entry) {
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

                if (entry.getPublishedDate() != null) {
                        article.setPublishedAt(entry.getPublishedDate().toInstant()
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }

                Source source = new Source();
                source.setName(sourceName);
                article.setSource(source);

                if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
                        article.setUrlToImage(entry.getEnclosures().get(0).getUrl());
                }

                return article;
        }
}