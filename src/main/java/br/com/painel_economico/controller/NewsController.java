package br.com.painel_economico.controller;

import br.com.painel_economico.dto.NewsResponseDTO;
import br.com.painel_economico.service.NewsService;
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
    public ResponseEntity<NewsResponseDTO> getTopHeadlines(
            @RequestParam(defaultValue = "br") String country,
            @RequestParam(defaultValue = "business") String category) {
        NewsResponseDTO response = newsService.getTopHeadlines(country, category);
        return ResponseEntity.ok(response);
    }
}