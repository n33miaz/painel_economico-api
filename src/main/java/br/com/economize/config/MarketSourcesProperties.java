package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Fontes de mercado alternativas à AwesomeAPI e o orçamento diário dela.
 *
 * <p>Todas as URLs são sobrescrevíveis por properties (prefixo
 * economize.market) por duas razões: os testes apontam para um servidor falso
 * sem tocar em código, e fonte pública muda de endereço sem aviso — o JSON
 * oficial do Tesouro Direto, por exemplo, passou a responder 410 Gone, e a
 * troca de endereço tem que ser property, não deploy.
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.market")
public class MarketSourcesProperties {

    /**
     * Teto diário de chamadas à AwesomeAPI (contador em memória, por dia UTC).
     * Estourado, a Home vai direto às fontes alternativas sem nem tentar — um
     * 429 a mais só aprofunda o bloqueio. Ver a conta em
     * {@link br.com.economize.service.provider.AwesomeApiBudget}.
     */
    private int awesomeDailyBudget = 120;

    /** Frankfurter: câmbio de referência do BCE, sem chave. */
    private String frankfurterUrl = "https://api.frankfurter.app";

    /** CoinGecko: cripto em BRL com variação 24h, sem chave (~30 req/min). */
    private String coingeckoUrl = "https://api.coingecko.com/api/v3";

    /** SGS do Banco Central: séries temporais (CDI, Selic, IPCA, PTAX, poupança...). */
    private String bcbSgsUrl = "https://api.bcb.gov.br/dados/serie";

    /** PTAX (Olinda/BCB): boletim do dólar, terceira opção para o USD. */
    private String ptaxUrl = "https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata";

    /**
     * JSON oficial do Tesouro Direto (preços intradiários e aplicação mínima).
     * Em 06/09/2026 responde 410 Gone com e sem User-Agent de navegador; fica
     * como primeira tentativa porque, se voltar, é a fonte mais rica.
     */
    private String treasuryUrl = "https://www.tesourodireto.com.br/json/br/com/b3/tesourodireto/service/api/treasurybondsinfo.json";

    /**
     * CSV "Taxas dos Títulos Ofertados pelo Tesouro Direto" (Tesouro
     * Transparente/CKAN): histórico inteiro, ~14 MB, do dia mais recente para o
     * mais antigo. Só as primeiras linhas interessam — ver TreasuryService.
     */
    private String treasuryCsvUrl = "https://www.tesourotransparente.gov.br/ckan/dataset/df56aa42-484a-4a59-8184-7676580c81e3/resource/796d2059-14e9-44e3-80c9-2d9e30b405c1/download/precotaxatesourodireto.csv";

    /** Stooq: cotação em CSV de ativos estrangeiros (vt.us). */
    private String stooqUrl = "https://stooq.com/q/l/";

    /** Yahoo Finance (chart v8): cotação de ETF/ação estrangeira, sem chave. */
    private String yahooUrl = "https://query1.finance.yahoo.com/v8/finance/chart";

    /**
     * User-Agent de navegador para as fontes que recusam cliente anônimo: o
     * Yahoo responde 429 "Edge: Too Many Requests" sem ele e 200 com ele.
     */
    private String browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";
}
