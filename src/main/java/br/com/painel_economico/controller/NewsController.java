package br.com.painel_economico.controller;

import br.com.painel_economico.dto.NewsResponseDTO;
import br.com.painel_economico.service.NewsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping("/top-headlines")
    public ResponseEntity<NewsResponseDTO> getTopHeadlines() {
        NewsResponseDTO response = newsService.getTopHeadlines();
        return ResponseEntity.ok(response);
    }
}