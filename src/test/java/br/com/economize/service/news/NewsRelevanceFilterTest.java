package br.com.economize.service.news;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsTopicInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsRelevanceFilterTest {

    private final NewsRelevanceFilter filter = new NewsRelevanceFilter();

    private static NewsArticle article(String category, String title, String description) {
        NewsArticle article = new NewsArticle();
        article.setSourceCategory(category);
        article.setTitle(title);
        article.setDescription(description);
        return article;
    }

    @Test
    @DisplayName("Futebol vindo de feed geral fica fora do radar")
    void footballFromGeneralFeedMustBeRejected() {
        NewsArticle article = article("geral", "Flamengo vence o Palmeiras e assume a liderança do Brasileirão",
                "Gol de pênalti no fim decidiu o jogo no Maracanã.");

        assertFalse(filter.classify(article));
    }

    @Test
    @DisplayName("Copom e Selic em feed geral entram, etiquetados com selic-cdi")
    void selicFromGeneralFeedMustBeAccepted() {
        NewsArticle article = article("geral", "Copom mantém Selic em 15% ao ano",
                "Decisão foi unânime; comitê vê inflação ainda acima da meta.");

        assertTrue(filter.classify(article));
        assertTrue(article.getTopics().contains("selic-cdi"));
        assertTrue(article.getTopics().contains("inflacao"));
    }

    @Test
    @DisplayName("Feed geral sem tópico financeiro nenhum fica fora, mesmo sem cair na exclusão")
    void generalFeedWithoutTopicMustBeRejected() {
        NewsArticle article = article("geral", "Prefeitura inaugura nova escola na zona norte",
                "Unidade atende 600 alunos do ensino fundamental.");

        assertFalse(filter.classify(article));
        assertTrue(article.getTopics().isEmpty());
    }

    @Test
    @DisplayName("Artigo de feed financeiro sem tópico entra assim mesmo")
    void financialFeedWithoutTopicMustBeAccepted() {
        NewsArticle article = article("mercados", "Empresa de logística anuncia novo CEO",
                "Executivo assume em outubro.");

        assertTrue(filter.classify(article));
        assertTrue(article.getTopics().isEmpty());
    }

    @Test
    @DisplayName("Em feed geral a exclusão vence mesmo com tópico financeiro")
    void exclusionWinsOnGeneralFeed() {
        NewsArticle article = article("geral", "Bolsa de apostas: Flamengo é favorito na final",
                "Casas de apostas pagam pouco pelo título rubro-negro.");

        assertFalse(filter.classify(article));
        assertTrue(article.getTopics().contains("bolsa"), "o tópico casa, mas a exclusão manda");
    }

    @Test
    @DisplayName("Em feed financeiro a exclusão só derruba quem não tem tópico nenhum")
    void exclusionOnFinancialFeedOnlyWithoutTopics() {
        NewsArticle semTopico = article("economia", "Morre ator famoso aos 80 anos",
                "Artista fez carreira em novelas.");
        NewsArticle comTopico = article("economia", "Ações da Gol disparam na bolsa após acordo com credores",
                "Papéis subiram 12% no pregão desta quinta.");

        assertFalse(filter.classify(semTopico));
        assertTrue(filter.classify(comTopico));
        assertTrue(comTopico.getTopics().contains("bolsa"));

        // caso real do Money Times: "copa" é exclusão, mas é um torneio de traders
        NewsArticle copaTrader = article("mercados", "Inscrições para a Copa BTG Trader 2026 terminam hoje",
                "Veja como participar.");
        assertTrue(filter.classify(copaTrader));
    }

    @Test
    @DisplayName("Casamento ignora acento e caixa, mas exige palavra inteira")
    void matchingIsAccentInsensitiveAndWholeWord() {
        NewsArticle acentos = article("geral", "INFLAÇÃO desacelera em agosto, diz IBGE", "");
        assertTrue(filter.classify(acentos));
        assertTrue(acentos.getTopics().contains("inflacao"));
        assertTrue(acentos.getTopics().contains("macro-br"));

        // "Google" não é "gol"; "Eurocopa" não é "euro"
        NewsArticle google = article("geral", "Google lança novo modelo de IA", "");
        assertFalse(filter.classify(google), "sem tópico financeiro não entra; mas não por exclusão");
        NewsArticle eurocopa = article("economia", "Eurocopa movimenta o turismo europeu", "");
        assertTrue(filter.classify(eurocopa));
        assertFalse(eurocopa.getTopics().contains("cambio"));
    }

    @Test
    @DisplayName("Expressões de mercado com palavra da exclusão não são excluídas")
    void marketPhrasesEscapeExclusion() {
        NewsArticle article = article("geral", "O jogo de mercado por trás da alta da Selic",
                "Gestores reposicionam carteiras após o Copom.");

        assertTrue(filter.classify(article));
    }

    @Test
    @DisplayName("Fonte sem categoria é tratada como geral (exigente)")
    void unknownCategoryIsStrict() {
        NewsArticle semCategoria = article(null, "Empresa anuncia novo CEO", "");
        assertFalse(filter.classify(semCategoria));

        NewsArticle tecnologia = article("tecnologia", "Nubank lança conta digital para adolescentes", "");
        assertTrue(filter.classify(tecnologia));
    }

    @Test
    @DisplayName("filter() mantém a ordem e devolve só quem passou")
    void filterKeepsOrderOfAccepted() {
        NewsArticle a = article("geral", "Dólar cai a R$ 5,10", "");
        NewsArticle b = article("geral", "BBB tem eliminação surpresa", "");
        NewsArticle c = article("geral", "Tesouro Direto: títulos pagam IPCA + 7%", "");

        List<NewsArticle> result = filter.filter(List.of(a, b, c));

        assertEquals(List.of(a, c), result);
        assertEquals(List.of("cambio"), a.getTopics());
        assertTrue(c.getTopics().containsAll(List.of("tesouro", "inflacao")));
    }

    @Test
    @DisplayName("Vocabulário fixo: 13 tópicos, ids em kebab-case e rótulos em pt-BR")
    void vocabularyIsFixed() {
        List<NewsTopicInfo> vocabulary = filter.vocabulary();

        assertEquals(List.of("selic-cdi", "tesouro", "inflacao", "cambio", "bolsa", "etf-exterior", "cripto",
                "fiis", "renda-fixa", "previdencia", "macro-br", "macro-global", "financas-pessoais"),
                vocabulary.stream().map(NewsTopicInfo::id).toList());
        assertEquals(List.of("Selic e CDI", "Tesouro Direto", "Inflação", "Câmbio", "Bolsa", "ETFs e exterior",
                "Cripto", "Fundos imobiliários", "Renda fixa", "Previdência", "Economia Brasil", "Economia global",
                "Finanças pessoais"),
                vocabulary.stream().map(NewsTopicInfo::label).toList());
    }

    @Test
    @DisplayName("knownTopics descarta ids desconhecidos e vira null quando não sobra nenhum")
    void knownTopicsDropsUnknownIds() {
        assertNull(filter.knownTopics(null));
        assertNull(filter.knownTopics(Set.of("nao-existe")));
        assertEquals(Set.of("cripto"), filter.knownTopics(Set.of("cripto", "nao-existe")));
    }
}
