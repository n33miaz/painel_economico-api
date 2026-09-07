package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.NewsSourceInfo;
import br.com.economize.dto.NewsSourcesResponse;
import br.com.economize.dto.NewsTopicInfo;
import br.com.economize.dto.NewsTopicsResponse;
import br.com.economize.dto.Source;
import br.com.economize.service.NewsService;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(NewsController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
class NewsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private NewsService newsService;

    private NewsResponse mockResponse() {
        NewsArticle article = new NewsArticle();
        article.setTitle("Mercado sobe hoje");
        article.setDescription("Bolsa de valores fecha em alta.");
        article.setSourceCategory("mercados");
        article.setTopics(List.of("bolsa"));
        Source source = new Source();
        source.setId("infomoney");
        source.setName("InfoMoney");
        article.setSource(source);

        NewsResponse response = new NewsResponse();
        response.setStatus("ok");
        response.setTotalResults(1);
        response.setUpdatedAt(Instant.parse("2026-08-14T12:00:00Z"));
        response.setArticles(List.of(article));
        return response;
    }

    @Test
    @DisplayName("GET /top-headlines - Contrato antigo (country/category) segue funcionando")
    void shouldKeepLegacyContractWorking() {
        when(newsService.getTopHeadlines(any(NewsQuery.class)))
                .thenReturn(Mono.just(mockResponse()));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/news/top-headlines")
                        .queryParam("country", "br")
                        .queryParam("category", "business")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.updatedAt").isEqualTo("2026-08-14T12:00:00Z")
                .jsonPath("$.articles[0].title").isEqualTo("Mercado sobe hoje")
                .jsonPath("$.articles[0].source.name").isEqualTo("InfoMoney")
                .jsonPath("$.articles[0].sourceCategory").isEqualTo("mercados")
                .jsonPath("$.articles[0].topics[0]").isEqualTo("bolsa");

        // categoria legada "business" não pode virar filtro real
        ArgumentCaptor<NewsQuery> captor = ArgumentCaptor.forClass(NewsQuery.class);
        verify(newsService).getTopHeadlines(captor.capture());
        assertThat(captor.getValue().category()).isNull();
        assertThat(captor.getValue().sources()).isNull();
        assertThat(captor.getValue().topics()).isNull();
    }

    @Test
    @DisplayName("GET /top-headlines - Novos filtros são normalizados e repassados ao serviço")
    void shouldForwardNewFiltersToService() {
        when(newsService.getTopHeadlines(any(NewsQuery.class)))
                .thenReturn(Mono.just(mockResponse()));

        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/news/top-headlines")
                        .queryParam("sources", "infomoney,g1-economia")
                        .queryParam("region", "BR")
                        .queryParam("category", "cripto")
                        .queryParam("q", "Petrobras")
                        .queryParam("topics", "Selic-CDI,tesouro")
                        .queryParam("limit", "5")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<NewsQuery> captor = ArgumentCaptor.forClass(NewsQuery.class);
        verify(newsService).getTopHeadlines(captor.capture());
        NewsQuery query = captor.getValue();
        assertThat(query.sources()).isEqualTo("infomoney,g1-economia");
        assertThat(query.region()).isEqualTo("br");
        assertThat(query.category()).isEqualTo("cripto");
        assertThat(query.q()).isEqualTo("petrobras");
        assertThat(query.topics()).isEqualTo("selic-cdi,tesouro");
        assertThat(query.topicIds()).containsExactly("selic-cdi", "tesouro");
        assertThat(query.limit()).isEqualTo(5);
    }

    @Test
    @DisplayName("GET /top-headlines - Deve retornar erro 500 quando o serviço falhar")
    void shouldReturnErrorWhenServiceFails() {
        when(newsService.getTopHeadlines(any(NewsQuery.class)))
                .thenReturn(Mono.error(new IllegalStateException("falha interna")));

        webTestClient.get()
                .uri("/api/v1/news/top-headlines")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("GET /sources - Deve listar as fontes disponíveis")
    void shouldListAvailableSources() {
        when(newsService.getSources()).thenReturn(new NewsSourcesResponse("ok", List.of(
                new NewsSourceInfo("infomoney", "InfoMoney", "br", "economia"),
                new NewsSourceInfo("coindesk", "CoinDesk", "global", "cripto"))));

        webTestClient.get()
                .uri("/api/v1/news/sources")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.sources.length()").isEqualTo(2)
                .jsonPath("$.sources[0].id").isEqualTo("infomoney")
                .jsonPath("$.sources[0].region").isEqualTo("br")
                .jsonPath("$.sources[1].category").isEqualTo("cripto");
    }

    @Test
    @DisplayName("GET /topics - Deve listar o vocabulário do radar com id e rótulo")
    void shouldListTopics() {
        when(newsService.getTopics()).thenReturn(new NewsTopicsResponse("ok", List.of(
                new NewsTopicInfo("selic-cdi", "Selic e CDI"),
                new NewsTopicInfo("tesouro", "Tesouro Direto"))));

        webTestClient.get()
                .uri("/api/v1/news/topics")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.topics.length()").isEqualTo(2)
                .jsonPath("$.topics[0].id").isEqualTo("selic-cdi")
                .jsonPath("$.topics[0].label").isEqualTo("Selic e CDI")
                .jsonPath("$.topics[1].id").isEqualTo("tesouro");
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken("teste@economize.app");
    }
}
