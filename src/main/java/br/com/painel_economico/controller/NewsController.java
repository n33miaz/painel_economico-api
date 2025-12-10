package br.com.painel_economico.controller;

import br.com.painel_economico.dto.NewsResponse;
import br.com.painel_economico.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
@Tag(name = "Notícias Financeiras", description = "Endpoints para busca de notícias do mercado")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @Operation(summary = "Manchetes Principais", description = "Retorna as principais notícias baseadas no país e categoria.")
    @GetMapping("/top-headlines")
    public Mono<ResponseEntity<NewsResponse>> getTopHeadlines(
            @Parameter(description = "Sigla do país (ex: br, us)", example = "br") @RequestParam(defaultValue = "br") String country,

            @Parameter(description = "Categoria da notícia (ex: business, technology)", example = "business") @RequestParam(defaultValue = "business") String category) {

        return newsService.getTopHeadlines(country, category)
                .map(response -> ResponseEntity.ok(response));
    }
}