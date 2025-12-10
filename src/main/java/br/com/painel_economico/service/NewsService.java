package br.com.painel_economico.service;

import br.com.painel_economico.dto.NewsArticle;
import br.com.painel_economico.dto.NewsResponse;
import br.com.painel_economico.dto.Source;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsService {

        private final WebClient webClient;

        @Value("${news.api.url}")
        private String newsApiUrl;

        @Value("${news.api.key}")
        private String newsApiKey;

        public NewsService(WebClient webClient) {
                this.webClient = webClient;
        }

        @Cacheable("news")
        public Mono<NewsResponse> getTopHeadlines(String country, String category) {
                System.out.println("--- INICIANDO BUSCA DE NOTÍCIAS ---");

                if (!StringUtils.hasText(newsApiKey)) {
                        System.err.println("ERRO: API Key não encontrada no .env");
                        return Mono.just(getFallbackNews());
                }

                String targetCountry = StringUtils.hasText(country) ? country : "br";
                String targetCategory = StringUtils.hasText(category) ? category : "business";

                return webClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .scheme("https")
                                                .host("newsapi.org")
                                                .path("/v2/top-headlines")
                                                .queryParam("country", targetCountry)
                                                .queryParam("category", targetCategory)
                                                .build())
                                .header("X-Api-Key", newsApiKey)
                                .header("User-Agent", "PainelEconomicoApp/1.0")
                                .retrieve()
                                .bodyToMono(NewsResponse.class)
                                .map(response -> {
                                        if (response.getArticles() == null || response.getArticles().isEmpty()) {
                                                System.out.println(
                                                                "ALERTA: A API retornou 0 notícias. Usando Fallback para preencher a tela.");
                                                return getFallbackNews();
                                        }
                                        System.out.println("SUCESSO: " + response.getTotalResults()
                                                        + " notícias reais encontradas.");
                                        return response;
                                })
                                .doOnError(e -> {
                                        System.err.println("ERRO TÉCNICO NA API: " + e.getMessage());
                                })
                                .onErrorResume(e -> Mono.just(getFallbackNews()));
        }

        // fallback com noticias mockadas
        private NewsResponse getFallbackNews() {
                NewsResponse response = new NewsResponse();
                response.setStatus("ok");

                List<NewsArticle> articles = new ArrayList<>();

                articles.add(createMockArticle(
                                "Ibovespa fecha em alta com otimismo no cenário fiscal",
                                "O principal índice da bolsa brasileira superou os 130 mil pontos impulsionado por grandes bancos e commodities.",
                                "InfoMoney"));

                articles.add(createMockArticle(
                                "Dólar recua 1,5% e fecha cotado a R$ 4,95",
                                "Moeda norte-americana perde força globalmente após dados de inflação nos Estados Unidos abaixo do esperado.",
                                "Valor Econômico"));

                articles.add(createMockArticle(
                                "Banco Central anuncia novas funcionalidades do Pix Automático",
                                "Nova modalidade facilitará pagamentos recorrentes de serviços de streaming e contas de consumo.",
                                "CNN Brasil"));

                articles.add(createMockArticle(
                                "Setor de Tecnologia lidera investimentos em 2025",
                                "Empresas de IA e Cloud Computing atraem capital estrangeiro e aquecem o mercado de trabalho.",
                                "TechTudo"));

                response.setArticles(articles);
                response.setTotalResults(articles.size());
                return response;
        }

        private NewsArticle createMockArticle(String title, String description, String sourceName) {
                NewsArticle article = new NewsArticle();
                article.setTitle(title);
                article.setDescription(description);
                article.setUrl("https://google.com/news");
                article.setUrlToImage(
                                "https://images.unsplash.com/photo-1590283603385-17ffb3a7f29f?q=80&w=1000&auto=format&fit=crop");
                article.setPublishedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

                Source source = new Source();
                source.setName(sourceName);
                article.setSource(source);

                return article;
        }
}