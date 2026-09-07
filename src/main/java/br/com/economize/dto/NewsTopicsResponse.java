package br.com.economize.dto;

import java.util.List;

/** Resposta do GET /api/v1/news/topics, no mesmo estilo de NewsSourcesResponse. */
public record NewsTopicsResponse(String status, List<NewsTopicInfo> topics) {
}
