package br.com.painel_economico.service;

import br.com.painel_economico.dto.NewsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private NewsService newsService;

    @BeforeEach
    void setUp() {
        newsService = new NewsService(webClient);
        ReflectionTestUtils.setField(newsService, "newsApiUrl", "https://newsapi.org/v2");
        ReflectionTestUtils.setField(newsService, "newsApiKey", "dummy-key");
    }

    @SuppressWarnings("unchecked")
    private void mockWebClientSuccess(NewsResponse response) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(NewsResponse.class)).thenReturn(Mono.just(response));
    }

    @Test
    @DisplayName("Deve retornar notícias quando a API responder com sucesso")
    void shouldReturnNewsSuccessfully() {
        NewsResponse mockResponse = new NewsResponse();
        mockResponse.setStatus("ok");
        mockResponse.setTotalResults(5);

        mockWebClientSuccess(mockResponse);

        Mono<NewsResponse> result = newsService.getTopHeadlines("br", "business");

        StepVerifier.create(result)
                .expectNextMatches(response -> response.getStatus().equals("ok") && response.getTotalResults() == 5)
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve lançar erro se a API Key não estiver configurada")
    void shouldErrorIfApiKeyIsMissing() {
        ReflectionTestUtils.setField(newsService, "newsApiKey", "");

        Mono<NewsResponse> result = newsService.getTopHeadlines("br", "business");

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof IllegalStateException &&
                        throwable.getMessage().contains("API Key de notícias não configurada"))
                .verify();
    }
}