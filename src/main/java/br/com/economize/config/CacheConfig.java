package br.com.economize.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * Validade do agregado do /all. Cada recarga custa 7 requisições à Brapi (uma
     * por ticker padrão), e a cota do plano é de ~1.000/dia:
     *
     * <pre>
     *   10 min → 144 recargas/dia × 7 = 1.008/dia  → sozinho já estoura a cota
     *   30 min →  48 recargas/dia × 7 =   336/dia  → cabe com folga
     * </pre>
     *
     * Meia hora é o mesmo frescor que o catálogo já dá às cotações de cauda
     * (economize.catalog.quote-ttl): preço um pouco mais velho na Home é
     * aceitável — Home vazia porque a cota acabou às 11h da manhã, não.
     */
    // sem modificador de propósito: a aritmética da cota diária é conferida em
    // teste, e ela depende deste número
    static final Duration INDICATORS_TTL = Duration.ofMinutes(30);

    /**
     * Cache curto da busca por termo. Existe para o caso comum de repetição
     * imediata (usuário digitando, voltando para a tela, puxando a mesma página
     * do catálogo de novo): dentro da janela, a repetição não custa cota
     * nenhuma. Curto de propósito — busca é a tela em que o usuário mais espera
     * ver preço de agora.
     */
    private static final Duration SEARCH_TTL = Duration.ofMinutes(5);

    /**
     * Série diária para o gráfico, por (moeda, janela). Uma hora porque a série
     * é diária — não muda em dez minutos — e porque cada consulta à AwesomeAPI
     * sai do mesmo orçamento que sustenta o /all da Home (AwesomeApiBudget):
     * com 10 min, vinte moedas em duas janelas custariam mais que a cota do dia.
     */
    static final Duration HISTORICAL_TTL = Duration.ofHours(1);

    /**
     * Indicadores macro (CDI, Selic, IPCA, PTAX, poupança, IGP-M). Seis horas:
     * CDI e PTAX mudam uma vez por dia, o resto uma vez por mês — e o SGS do
     * Banco Central é um serviço público que não precisa ser consultado a cada
     * abertura de tela.
     */
    static final Duration MACRO_TTL = Duration.ofHours(6);

    /** Títulos do Tesouro: a fonte que funciona hoje (CSV do Tesouro Transparente) é diária. */
    static final Duration TREASURY_TTL = Duration.ofHours(1);

    /** Cotação estrangeira (Yahoo/Stooq): mesmo frescor das outras cotações. */
    static final Duration FOREIGN_QUOTE_TTL = Duration.ofMinutes(30);

    @SuppressWarnings("null")
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("news");

        cacheManager.setAsyncCacheMode(true);

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats());

        // TTLs próprios: estes dois são os que se pagam em cota da Brapi, então
        // não podem seguir o default de 10 min dos demais
        cacheManager.registerCustomCache("indicators", Caffeine.newBuilder()
                .expireAfterWrite(INDICATORS_TTL)
                .maximumSize(100)
                .recordStats()
                .buildAsync());

        cacheManager.registerCustomCache("indicatorSearch", Caffeine.newBuilder()
                .expireAfterWrite(SEARCH_TTL)
                .maximumSize(300)
                .recordStats()
                .buildAsync());

        // e estes são os que se pagam em cota da AwesomeAPI ou em paciência de
        // serviço público: mais longos que o default de propósito
        cacheManager.registerCustomCache("historical", Caffeine.newBuilder()
                .expireAfterWrite(HISTORICAL_TTL)
                .maximumSize(200)
                .recordStats()
                .buildAsync());

        cacheManager.registerCustomCache("macro", Caffeine.newBuilder()
                .expireAfterWrite(MACRO_TTL)
                .maximumSize(10)
                .recordStats()
                .buildAsync());

        cacheManager.registerCustomCache("treasury", Caffeine.newBuilder()
                .expireAfterWrite(TREASURY_TTL)
                .maximumSize(10)
                .recordStats()
                .buildAsync());

        cacheManager.registerCustomCache("foreignQuote", Caffeine.newBuilder()
                .expireAfterWrite(FOREIGN_QUOTE_TTL)
                .maximumSize(200)
                .recordStats()
                .buildAsync());

        return cacheManager;
    }
}
