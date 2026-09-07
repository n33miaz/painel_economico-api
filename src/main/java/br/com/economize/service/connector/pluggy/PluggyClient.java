package br.com.economize.service.connector.pluggy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente mínimo da API do Pluggy (meu.pluggy.ai) — ADR-011. O usuário conecta
 * as contas dele no Meu Pluggy e traz clientId/clientSecret/itemIds do
 * dashboard; a API só lê. Chamadas blocantes de propósito: o sync roda no
 * boundedElastic como todo o pipeline de importação.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
public class PluggyClient {

    // A v2 fixa a página em 500 e não aceita pageSize; o teto abaixo é só a
    // trava contra cursor que não termina
    private static final int MAX_PAGES = 200;

    private final WebClient webClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;

    public PluggyClient(@Qualifier("pluggyWebClient") WebClient webClient,
                        @Value("${economize.pluggy.base-url}") String baseUrl,
                        @Value("${economize.pluggy.client-id}") String clientId,
                        @Value("${economize.pluggy.client-secret}") String clientSecret) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** POST /auth — apiKey de curta duração usada nas demais chamadas. */
    public String authenticate() {
        Map<String, Object> body = webClient.post()
                .uri(baseUrl + "/auth")
                .bodyValue(Map.of("clientId", clientId, "clientSecret", clientSecret))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
        if (body == null || body.get("apiKey") == null) {
            throw new IllegalStateException("Pluggy não devolveu apiKey — confira PLUGGY_CLIENT_ID/SECRET");
        }
        return String.valueOf(body.get("apiKey"));
    }

    /**
     * POST /connect_token — token de curta duração que abre o widget Pluggy
     * Connect no app. O clientUserId carimba o item criado com o dono (usamos o
     * UUID interno do usuário, nunca o e-mail: nada de PII no agregador) e é o
     * que permite recusar depois o registro de item alheio. Com itemId, o
     * widget abre em modo atualização da conexão existente (MFA/credencial
     * expirada) em vez de criar item novo.
     */
    public String connectToken(String apiKey, String clientUserId, String itemId) {
        Map<String, Object> payload = new HashMap<>();
        if (itemId != null && !itemId.isBlank()) payload.put("itemId", itemId);
        payload.put("options", Map.of("clientUserId", clientUserId));
        Map<String, Object> body = webClient.post()
                .uri(baseUrl + "/connect_token")
                .header("X-API-KEY", apiKey)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
        if (body == null || body.get("accessToken") == null) {
            throw new IllegalStateException("Pluggy não devolveu accessToken do Connect");
        }
        return String.valueOf(body.get("accessToken"));
    }

    /**
     * GET /items/{id} — detalhes de uma conexão. Devolve null quando o item não
     * existe (404 ITEM_NOT_FOUND): para o registro, "não existe no Pluggy" é um
     * caso de negócio, não uma falha de provedor.
     */
    public Map<String, Object> item(String apiKey, String itemId) {
        try {
            return webClient.get()
                    .uri(baseUrl + "/items/{itemId}", itemId)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            return null;
        }
    }

    /**
     * DELETE /items/{id} — apaga a conexão no Pluggy (revoga o consentimento no
     * agregador). 404 conta como sucesso: o item já não existia lá.
     */
    public void deleteItem(String apiKey, String itemId) {
        try {
            webClient.delete()
                    .uri(baseUrl + "/items/{itemId}", itemId)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.NotFound e) {
            // sem o itemId no texto: é identificador de terceiro e não tem por
            // que ficar em log de aplicação
            log.info("Item já não existia no Pluggy ao desvincular");
        }
    }

    /**
     * GET /accounts?itemId= — contas de uma conexão (item) do usuário, TODAS as
     * páginas.
     *
     * <p>A resposta é paginada como a de transações. Ler só a primeira página
     * era uma aposta silenciosa em "ninguém tem mais contas do que cabe numa
     * página": quem tem conta corrente, poupança, investimento e três cartões
     * no mesmo banco perderia contas inteiras do sync — e, pior, perderia
     * justamente o cartão que autoriza reconhecer o pagamento de fatura. Mesma
     * trava de páginas do {@code transactions}: {@code next} é dado de terceiro
     * e um cursor que se repetisse deixaria o sync girando para sempre.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> accounts(String apiKey, String itemId) {
        List<Map<String, Object>> all = new ArrayList<>();
        String query = "?itemId=" + itemId;

        for (int page = 0; page < MAX_PAGES && query != null && !query.isBlank(); page++) {
            Map<String, Object> body = webClient.get()
                    .uri(URI.create(baseUrl + "/accounts" + query))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
            if (body == null) break;
            Object results = body.get("results");
            if (results instanceof List<?> list) {
                all.addAll((List<Map<String, Object>>) list);
            }
            query = nextQuery(body.get("next"), baseUrl + "/accounts");
        }
        return all;
    }

    /**
     * Normaliza o cursor da próxima página. A API já devolveu {@code next} como
     * query string pronta ("?accountId=...&amp;after=...") e como URL absoluta,
     * dependendo do recurso e da versão — e um dia pode devolver caminho
     * relativo. Sem tratar os três, a URL montada viraria
     * ".../v2/transactionshttps://api.pluggy.ai/..." e o sync morreria na
     * segunda página, silenciosamente truncando o extrato do usuário.
     */
    private String nextQuery(Object next, String resourceUrl) {
        if (next == null) return null;
        String raw = String.valueOf(next).trim();
        // Jackson entrega null de JSON como o texto "null" em String.valueOf
        if (raw.isEmpty() || "null".equals(raw)) return null;
        if (raw.startsWith("?")) return raw;

        int queryStart = raw.indexOf('?');
        if (queryStart >= 0) {
            // URL absoluta ou caminho: só a query interessa, o recurso é o mesmo
            return raw.substring(queryStart);
        }
        // sem "?" não há como reaproveitar: parar é melhor do que repetir a
        // primeira página em laço
        log.warn("Cursor de paginação do Pluggy em formato não reconhecido para {}; encerrando a leitura",
                resourceUrl.substring(resourceUrl.lastIndexOf('/') + 1));
        return null;
    }

    /**
     * GET /v2/transactions — devolve todas as páginas da janela.
     *
     * <p>A v1 (`/transactions` com `page`/`pageSize`/`from`/`to`) foi
     * descontinuada e responde <b>410 ENDPOINT_DEPRECATED</b>. A v2 pagina por
     * cursor: o campo {@code next} da resposta já vem como a query string
     * pronta da próxima página, e vem vazio quando acabou. Os filtros de data
     * também mudaram de nome, para {@code dateFrom}/{@code dateTo}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> transactions(String apiKey, String accountId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> all = new ArrayList<>();
        String query = "?accountId=" + accountId + "&dateFrom=" + from + "&dateTo=" + to;

        // Trava de segurança: `next` é dado de terceiro e um cursor que se
        // repetisse deixaria o sync girando para sempre
        for (int page = 0; page < MAX_PAGES && query != null && !query.isBlank(); page++) {
            Map<String, Object> body = webClient.get()
                    // URI pronta, e não template: o cursor do `after` já vem
                    // percent-encoded e o uriBuilder escaparia o '%' de novo
                    .uri(URI.create(baseUrl + "/v2/transactions" + query))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
            if (body == null) break;
            Object results = body.get("results");
            if (results instanceof List<?> list) {
                all.addAll((List<Map<String, Object>>) list);
            }
            query = nextQuery(body.get("next"), baseUrl + "/v2/transactions");
        }
        return all;
    }

    /**
     * GET /investments?itemId= — posições de investimento de uma conexão,
     * TODAS as páginas.
     *
     * <p>É o recurso que o sync de extrato ignora de propósito: a conta
     * INVESTMENT do {@code /accounts} não tem lançamentos úteis, e o que
     * interessa dela é a POSIÇÃO (saldo, quantidade, taxa, vencimento), que
     * mora aqui. Mesma paginação e mesma trava de páginas de {@code accounts}:
     * {@code next} é dado de terceiro, e um cursor que se repetisse deixaria o
     * sync girando para sempre. O corpo volta cru ({@code Map}) porque o
     * contrato de investimento do Pluggy varia por tipo de ativo — quem sabe
     * quais campos existem em cada caso é o mapeador, não o cliente.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> investments(String apiKey, String itemId) {
        List<Map<String, Object>> all = new ArrayList<>();
        String query = "?itemId=" + itemId;

        for (int page = 0; page < MAX_PAGES && query != null && !query.isBlank(); page++) {
            Map<String, Object> body = webClient.get()
                    .uri(URI.create(baseUrl + "/investments" + query))
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
            if (body == null) break;
            Object results = body.get("results");
            if (results instanceof List<?> list) {
                all.addAll((List<Map<String, Object>>) list);
            }
            query = nextQuery(body.get("next"), baseUrl + "/investments");
        }
        return all;
    }
}
