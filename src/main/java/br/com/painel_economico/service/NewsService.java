package br.com.painel_economico.service;

import br.com.painel_economico.dto.NewsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Service
public class NewsService {

        private final WebClient webClient;

        @Value("${news.api.url}")
        private String newsApiUrl;

        @Value("${news.api.key}")
        private String newsApiKey;

        public NewsService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("news")
        public Mono<NewsResponse> getTopHeadlines(String country, String category) {
                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path(newsApiUrl + "/top-headlines")
                                                .queryParam("country", country)
                                                .queryParam("category", category)
                                                .queryParam("apiKey", newsApiKey)
                                                .build())
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, this::handleApiError)
                                .bodyToMono(NewsResponse.class);
        }

        private Mono<? extends Throwable> handleApiError(ClientResponse response) {
                return response.bodyToMono(String.class)
                                .flatMap(errorBody -> Mono.error(new WebClientResponseException(
                                                response.statusCode().value(),
                                                "Erro na API de Notícias: " + errorBody,
                                                response.headers().asHttpHeaders(),
                                                errorBody.getBytes(),
                                                null)));
        }
}