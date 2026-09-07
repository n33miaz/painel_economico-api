package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Catálogo de feeds RSS agregados pelo endpoint de notícias. Os defaults vivem
 * aqui em código; qualquer feed pode ser sobrescrito/adicionado via properties
 * (economize.news.feeds[N].id/.name/.url/.region/.category) sem recompilar.
 *
 * <p>Todo feed do catálogo foi testado com o mesmo User-Agent do agregador
 * ({@code curl -L -A "EconomizeApp/2.0 RSS Aggregator"}) e o resultado está
 * anotado ao lado de cada um. Candidatos testados e deixados de fora em
 * 2026-09-06, para ninguém repetir o teste à toa:
 * <ul>
 * <li>CNN Brasil raiz ({@code cnnbrasil.com.br/feed/}): vivo, mas mistura
 * futebol e entretenimento; a editoria de economia
 * ({@code /economia/feed/}) segue 404 — a CNN saiu do catálogo;</li>
 * <li>InfoMoney por editoria ({@code /mercados/feed/}, {@code /economia/feed/},
 * {@code /onde-investir/feed/}): 200, mas o canal vem sem nenhum item;</li>
 * <li>E-Investidor Estadão ({@code einvestidor.estadao.com.br/feed/}): 403;</li>
 * <li>Poder360 Economia ({@code /economia/feed/}): responde HTML, não RSS;</li>
 * <li>Mais Retorno ({@code maisretorno.com/blog/feed}): 404;</li>
 * <li>Investing.com BR ({@code br.investing.com/rss/news_25.rss}): RSS válido,
 * mas o pubDate não segue o RFC 822 e o parser descarta a data — o artigo
 * ficaria sem ordenação e sem corte por idade.</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.news")
public class NewsFeedsProperties {

    /**
     * Tempo máximo de espera por feed. Como a busca roda em segundo plano, um
     * feed lento já não segura o usuário — o limite existe para o ciclo do
     * agendador terminar em tempo previsível.
     */
    private Duration feedTimeout = Duration.ofSeconds(8);

    /**
     * Máximo de artigos aproveitados de cada feed. Vinte, e não dez, porque o
     * radar de relevância descarta parte deles depois e o dono quer MAIS
     * notícias no agregado, não menos.
     */
    private int itemsPerFeed = 20;

    /**
     * Idade máxima de um artigo no agregado. Além disso a notícia não é mais
     * notícia, e uma fonte que parou de publicar não fica congelada na tela.
     */
    private Duration maxAge = Duration.ofDays(3);

    /**
     * Quantos feeds o ciclo baixa ao mesmo tempo. Dois, porque na CPU de 0,1 do
     * plano free onze handshakes TLS simultâneos estouravam todos os timeouts
     * no mesmo segundo.
     */
    private int concurrency = 2;

    private List<Feed> feeds = List.of(
            // 2026-09-06: 200 em 0,10 s, 120 KB, 10 itens
            Feed.of("infomoney", "InfoMoney", "https://www.infomoney.com.br/feed/", "br", "economia"),
            // 2026-09-06: 200 em 0,11 s, 317 KB, 30 itens
            Feed.of("investnews", "InvestNews", "https://investnews.com.br/feed/", "br", "economia"),
            // 2026-09-06: 200 em 0,07 s, 622 KB, 100 itens — o maior do catálogo; o
            // servidor manda gzip, e é por ele que o rssWebClient pede compressão
            Feed.of("g1-economia", "G1 Economia", "https://g1.globo.com/rss/g1/economia/", "br", "economia"),
            // 2026-09-06: 200 em 0,08 s, 43 KB, 25 itens
            Feed.of("exame", "Exame", "https://exame.com/feed/", "br", "economia"),
            // 2026-09-06: 200 em 0,06 s, 93 KB, 10 itens
            Feed.of("seudinheiro", "Seu Dinheiro", "https://www.seudinheiro.com/feed/", "br", "economia"),
            // 2026-09-06: 200 em 0,05 s, 49 KB, 10 itens
            Feed.of("moneytimes", "Money Times", "https://www.moneytimes.com.br/feed/", "br", "mercados"),
            // BM&C News: o host com "www" falha o handshake TLS; usar sem "www".
            // 2026-09-06: 200 em 0,09 s, 80 KB, 10 itens
            Feed.of("bmcnews", "BM&C News", "https://bmcnews.com.br/feed/", "br", "mercados"),
            // 2026-09-06: 200 em 0,20 s, 506 KB, 100 itens
            Feed.of("valor-investe", "Valor Investe", "https://valorinveste.globo.com/rss/valorinveste/", "br",
                    "investimentos"),
            // 2026-09-06: 200 em 1,1 s, 67 KB, 10 itens
            Feed.of("suno", "Suno Notícias", "https://www.suno.com.br/noticias/feed/", "br", "investimentos"),
            // 2026-09-06: 200 em 0,35 s, 75 KB, 10 itens
            Feed.of("brazil-journal", "Brazil Journal", "https://braziljournal.com/feed/", "br", "mercados"),
            // 2026-09-06: 200 em 0,27 s, 87 KB, 10 itens
            Feed.of("agencia-brasil-economia", "Agência Brasil Economia",
                    "https://agenciabrasil.ebc.com.br/rss/economia/feed.xml", "br", "economia"),
            // 2026-09-06: 200 em 0,28 s, 154 KB, 20 itens
            Feed.of("estadao-economia", "Estadão Economia",
                    "https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/economia/", "br", "economia"),
            // 2026-09-06: 200 em 0,08 s, 30 KB, 50 itens
            Feed.of("yahoo-finance", "Yahoo Finance", "https://finance.yahoo.com/news/rssindex", "global",
                    "mercados"),
            // 2026-09-06: 200 em 0,13 s, 31 KB, 25 itens
            Feed.of("coindesk", "CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/", "global",
                    "cripto"),
            // 2026-09-06: 200 em 0,10 s, 47 KB, 30 itens
            Feed.of("cointelegraph", "Cointelegraph", "https://cointelegraph.com/rss", "global", "cripto"));

    @Data
    public static class Feed {
        private String id;
        private String name;
        private String url;
        /** Região da fonte: "br" ou "global". */
        private String region = "br";
        /** Categoria editorial: economia, mercados, investimentos, cripto, geral... */
        private String category = "economia";

        public static Feed of(String id, String name, String url, String region, String category) {
            Feed feed = new Feed();
            feed.setId(id);
            feed.setName(name);
            feed.setUrl(url);
            feed.setRegion(region);
            feed.setCategory(category);
            return feed;
        }
    }
}
