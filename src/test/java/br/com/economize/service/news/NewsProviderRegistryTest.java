package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class NewsProviderRegistryTest {

    @Mock
    private WebClient rssWebClient;

    private NewsProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NewsProviderRegistry(new NewsFeedsProperties(), rssWebClient);
    }

    @Test
    @DisplayName("Deve montar os provedores default a partir da configuração")
    void shouldBuildDefaultProviders() {
        List<String> ids = registry.getAll().stream().map(NewsProvider::getId).toList();

        assertEquals(15, ids.size());
        assertTrue(ids.containsAll(List.of(
                "infomoney", "investnews", "g1-economia", "exame", "seudinheiro", "moneytimes", "bmcnews",
                "valor-investe", "suno", "brazil-journal", "agencia-brasil-economia", "estadao-economia",
                "yahoo-finance", "coindesk", "cointelegraph")));
        // feed morto (410 Gone) foi removido do catálogo
        assertTrue(ids.stream().noneMatch(id -> id.contains("cointelegraph-br")));
        // o feed raiz da CNN (categoria geral) era a origem do futebol no radar
        assertTrue(ids.stream().noneMatch(id -> id.contains("cnn")));
        // sem id repetido: o id é a chave do snapshot por feed
        assertEquals(ids.size(), Set.copyOf(ids).size());
    }

    @Test
    @DisplayName("Todo feed default é de uma categoria financeira")
    void defaultFeedsMustBeFinancial() {
        Set<String> financeiras = Set.of("economia", "mercados", "investimentos", "cripto");
        assertTrue(registry.getAll().stream().allMatch(p -> financeiras.contains(p.getCategory())),
                "feed 'geral' no catálogo default traria de volta esporte e entretenimento");
    }

    @Test
    @DisplayName("Deve selecionar por ids de fontes")
    void shouldSelectBySourceIds() {
        List<NewsProvider> selected = registry.select(Set.of("infomoney", "coindesk"), null, null);

        assertEquals(2, selected.size());
    }

    @Test
    @DisplayName("Deve selecionar por região e categoria")
    void shouldSelectByRegionAndCategory() {
        List<NewsProvider> globais = registry.select(null, "global", null);
        assertTrue(globais.stream().allMatch(p -> p.getRegion().equals("global")));
        assertEquals(3, globais.size());

        List<NewsProvider> cripto = registry.select(null, null, "cripto");
        assertTrue(cripto.stream().allMatch(p -> p.getCategory().equals("cripto")));
        assertEquals(2, cripto.size());

        List<NewsProvider> investimentos = registry.select(null, "br", "investimentos");
        assertEquals(2, investimentos.size());

        List<NewsProvider> criptoBr = registry.select(null, "br", "cripto");
        assertEquals(0, criptoBr.size());
    }

    @Test
    @DisplayName("Sem filtros deve retornar todos os provedores")
    void shouldReturnAllWithoutFilters() {
        assertEquals(registry.getAll().size(), registry.select(null, null, null).size());
    }
}
