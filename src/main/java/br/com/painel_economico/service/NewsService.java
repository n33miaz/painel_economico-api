package br.com.painel_economico.service;

import br.com.painel_economico.dto.NewsResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    public NewsResponseDTO getTopHeadlines(String country, String category) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(newsApiUrl + "/top-headlines")
                        .queryParam("country", country)
                        .queryParam("category", category)
                        .queryParam("apiKey", newsApiKey)
                        .build())
                .retrieve()
                .bodyToMono(NewsResponseDTO.class)
                .block();
    }
}