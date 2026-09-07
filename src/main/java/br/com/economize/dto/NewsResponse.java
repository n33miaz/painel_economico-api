package br.com.economize.dto;

import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class NewsResponse {
    private String status;
    private int totalResults;
    /**
     * Instante em que o agregado em memória foi atualizado pela última vez com
     * sucesso. Null só no boot frio, antes de qualquer feed responder: o app usa
     * isso para mostrar "atualizado há X min" em vez de fingir que é ao vivo.
     */
    private Instant updatedAt;
    private List<NewsArticle> articles;
}
