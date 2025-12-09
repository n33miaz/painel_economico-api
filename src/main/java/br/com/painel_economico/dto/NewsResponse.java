package br.com.painel_economico.dto;

import java.util.List;
import lombok.Data;

@Data
public class NewsResponse {
    private String status;
    private int totalResults;
    private List<NewsArticle> articles;
}