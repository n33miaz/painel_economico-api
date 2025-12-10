package br.com.painel_economico.controller;

import br.com.painel_economico.dto.NewsArticle;
import br.com.painel_economico.dto.NewsResponse;
import br.com.painel_economico.dto.Source;
import br.com.painel_economico.service.NewsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@WebFluxTest(NewsController.class)
class NewsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private NewsService newsService;

    @Test
    @DisplayName("GET /top-headlines - Deve retornar notícias com sucesso")
    void shouldReturnTopHeadlines() {
        NewsArticle article = new NewsArticle();
        article.setTitle("Mercado sobe hoje");
        article.setDescription("Bolsa de valores fecha em alta.");
        Source source = new Source();
        source.setName("InfoMoney");
        article.setSource(source);

        NewsResponse mockResponse = new NewsResponse();
        mockResponse.setStatus("ok");
        mockResponse.setTotalResults(1);
        mockResponse.setArticles(List.of(article));

        when(newsService.getTopHeadlines(anyString(), anyString()))
                .thenReturn(Mono.just(mockResponse));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/news/top-headlines")
                        .queryParam("country", "br")
                        .queryParam("category", "business")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.articles[0].title").isEqualTo("Mercado sobe hoje")
                .jsonPath("$.articles[0].source.name").isEqualTo("InfoMoney");
    }

    @Test
    @DisplayName("GET /top-headlines - Deve retornar erro 500 quando API Key não configurada")
    void shouldReturnErrorWhenServiceFails() {
        when(newsService.getTopHeadlines(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("API Key missing")));

        webTestClient.get()
                .uri("/api/news/top-headlines")
                .exchange()
                .expectStatus().is5xxServerError();
    }
}