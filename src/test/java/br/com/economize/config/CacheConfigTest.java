package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O gerenciador de cache é fechado (não cria cache sob demanda): um nome usado
 * em {@code @Cacheable} que não esteja declarado aqui só explode em produção, na
 * primeira chamada — e justo nas rotas que sustentam a Home.
 */
class CacheConfigTest {

    private final CacheManager cacheManager = new CacheConfig().cacheManager();

    @Test
    @DisplayName("Todo cache usado por @Cacheable precisa existir no gerenciador")
    void everyDeclaredCacheMustResolve() {
        for (String name : new String[] { "indicators", "indicatorSearch", "news", "historical", "macro",
                "treasury", "foreignQuote" }) {
            assertNotNull(cacheManager.getCache(name), "cache ausente: " + name);
        }
    }

    @Test
    @DisplayName("O cache do /all precisa de TTL próprio, maior que o default")
    void indicatorsCacheMustKeepItsOwnTtl() {
        // é este TTL que faz o pior caso do /all caber na cota diária da Brapi
        // (24h / 30min = 48 recargas × 7 tickers = 336/dia)
        assertTrue(CacheConfig.INDICATORS_TTL.toMinutes() >= 30,
                "TTL menor que 30 min faz o /all sozinho estourar a cota do plano");
    }
}
