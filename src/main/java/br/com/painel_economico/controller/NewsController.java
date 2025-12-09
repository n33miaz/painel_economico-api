package br.com.painel_economico.controller;

import br.com.painel_economico.dto.NewsResponse;
import br.com.painel_economico.service.NewsService;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/top-headlines")
    public Mono<ResponseEntity<NewsResponse>> getTopHeadlines(
            @RequestParam(defaultValue = "br") String country,
            @RequestParam(defaultValue = "business") String category) {
        return newsService.getTopHeadlines(country, category)
                .map(response -> ResponseEntity.ok(response));
    }
}