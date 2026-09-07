package br.com.economize.controller;

import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.NewsSourcesResponse;
import br.com.economize.dto.NewsTopicsResponse;
import br.com.economize.service.NewsService;
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
@RequestMapping("/api/v1/news")
@Tag(name = "Notícias Financeiras", description = "Endpoints para busca de notícias do mercado")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @Operation(summary = "Manchetes Principais", description = "Notícias mais recentes do agregado em memória, "
            + "atualizado em segundo plano a partir das fontes RSS configuradas e restrito a economia/investimentos. "
            + "Todos os filtros são opcionais; sem filtros, retorna o agregado completo.")
    @GetMapping("/top-headlines")
    public Mono<ResponseEntity<NewsResponse>> getTopHeadlines(
            @Parameter(description = "Sigla do país (parâmetro legado, aceito e ignorado)", example = "br", deprecated = true) @RequestParam(required = false) String country,

            @Parameter(description = "Categoria das fontes (economia, mercados, investimentos, cripto). "
                    + "Valores do contrato antigo (ex.: business) seguem aceitos e ignorados.", example = "economia") @RequestParam(required = false) String category,

            @Parameter(description = "IDs de fontes separados por vírgula (ver GET /news/sources)", example = "infomoney,g1-economia") @RequestParam(required = false) String sources,

            @Parameter(description = "Região das fontes: br ou global", example = "br") @RequestParam(required = false) String region,

            @Parameter(description = "Busca textual em título e descrição (ex.: nome de um ativo)", example = "petrobras") @RequestParam(required = false) String q,

            @Parameter(description = "IDs de tópicos separados por vírgula (ver GET /news/topics); "
                    + "ids desconhecidos são ignorados", example = "selic-cdi,tesouro") @RequestParam(required = false) String topics,

            @Parameter(description = "Máximo de artigos na resposta; sem valor, retorna tudo", example = "20") @RequestParam(required = false) Integer limit) {

        return newsService.getTopHeadlines(NewsQuery.of(sources, region, category, q, topics, limit))
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Fontes de Notícias", description = "Lista as fontes disponíveis (id, nome, região, categoria) "
            + "para o app montar a configuração de preferências.")
    @GetMapping("/sources")
    public ResponseEntity<NewsSourcesResponse> getSources() {
        return ResponseEntity.ok(newsService.getSources());
    }

    @Operation(summary = "Tópicos do radar", description = "Vocabulário fixo de tópicos (id, rótulo em pt-BR) "
            + "com que cada notícia é etiquetada; os ids valem no filtro ?topics= das manchetes.")
    @GetMapping("/topics")
    public ResponseEntity<NewsTopicsResponse> getTopics() {
        return ResponseEntity.ok(newsService.getTopics());
    }
}
