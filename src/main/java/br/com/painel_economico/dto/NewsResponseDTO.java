package br.com.painel_economico.dto;

import java.util.List;
import lombok.Data;

@Data
public class NewsResponseDTO {
    private String status;
    private int totalResults;
    private List<NewsArticleDTO> articles;
}