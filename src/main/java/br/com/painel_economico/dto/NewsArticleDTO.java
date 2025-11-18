package br.com.painel_economico.dto;

import lombok.Data;

@Data
public class NewsArticleDTO {
    private SourceDTO source;
    private String author;
    private String title;
    private String description;
    private String url;
    private String urlToImage;
    private String publishedAt;
    private String content;
}