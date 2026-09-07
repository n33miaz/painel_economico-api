package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.RegisterConnectionRequest;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A rota neutra do conector com um provedor dublado: o controller não pode
 * saber (nem dizer ao app) quem está por trás.
 */
@WebFluxTest(ConnectorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class ConnectorControllerTest {

    private static final String EMAIL = "teste@economize.app";
    private static final String ITEM_ID = "0f8a8c0e-1111-2222-3333-444455556666";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private OpenFinanceProvider provider;

    @BeforeEach
    void provedorDublado() {
        when(provider.id()).thenReturn("dublado");
        when(provider.displayName()).thenReturn("Open Finance");
        when(provider.enabled()).thenReturn(true);
        when(provider.widget()).thenReturn(
                new OpenFinanceProvider.WidgetDescriptor("https://cdn.example/widget.js", "dublado-connect"));
    }

    @Test
    @DisplayName("GET /status - enabled/configured/itemCount, provedor só com nome neutro e o widget a carregar")
    void statusTrazProvedorNeutroEWidget() {
        when(provider.status(EMAIL)).thenReturn(new OpenFinanceProvider.ProviderStatus(true, true, 2));

        webTestClient.get()
                .uri("/api/v1/connectors/status")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.configured").isEqualTo(true)
                .jsonPath("$.itemCount").isEqualTo(2)
                .jsonPath("$.provider.id").isEqualTo("dublado")
                .jsonPath("$.provider.displayName").isEqualTo("Open Finance")
                .jsonPath("$.widget.scriptUrl").isEqualTo("https://cdn.example/widget.js")
                .jsonPath("$.widget.kind").isEqualTo("dublado-connect")
                // o campo legado "owner" não existe na rota neutra
                .jsonPath("$.owner").doesNotExist();
    }

    @Test
    @DisplayName("GET /status - Sem token deve retornar 401")
    void statusExigeToken() {
        webTestClient.get()
                .uri("/api/v1/connectors/status")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(provider, never()).status(anyString());
    }

    @Test
    @DisplayName("POST /connect-token - Devolve só o accessToken; com ?itemId= repassa o modo atualização")
    void connectTokenDelegaAoProvedor() {
        when(provider.connectToken(EMAIL, null)).thenReturn("widget-token");
        when(provider.connectToken(EMAIL, ITEM_ID)).thenReturn("update-token");

        webTestClient.post()
                .uri("/api/v1/connectors/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("widget-token")
                .jsonPath("$.apiKey").doesNotExist();

        webTestClient.post()
                .uri("/api/v1/connectors/connect-token?itemId=" + ITEM_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("update-token");
    }

    @Test
    @DisplayName("POST /items - 201 com a conexão criada, connectorName e institution iguais")
    void registerDevolveConexaoNeutra() {
        when(provider.registerItem(EMAIL, ITEM_ID)).thenReturn(ConnectionResponse.of(
                UUID.randomUUID(), ITEM_ID, 201L, "Banco Inter",
                OffsetDateTime.parse("2026-08-15T12:00:00Z"), null));

        webTestClient.post()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterConnectionRequest(ITEM_ID))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.itemId").isEqualTo(ITEM_ID)
                .jsonPath("$.connectorName").isEqualTo("Banco Inter")
                .jsonPath("$.institution").isEqualTo("Banco Inter")
                .jsonPath("$.lastSyncedAt").isEmpty();
    }

    @Test
    @DisplayName("POST /items - itemId em branco responde 400 sem chamar o provedor")
    void registerRejeitaItemIdEmBranco() {
        webTestClient.post()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterConnectionRequest("  "))
                .exchange()
                .expectStatus().isBadRequest();

        verify(provider, never()).registerItem(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /items - Conexão inexistente no provedor responde 404; já registrada responde 409")
    void registerPropagaNaoEncontradoEConflito() {
        when(provider.registerItem(EMAIL, ITEM_ID))
                .thenThrow(new ResourceNotFoundException("Item não encontrado"))
                .thenThrow(new ResourceConflictException("Este item já está registrado"));

        webTestClient.post()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterConnectionRequest(ITEM_ID))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");

        webTestClient.post()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterConnectionRequest(ITEM_ID))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("GET /items - Lista as conexões do usuário com a instituição")
    void listDevolveConexoes() {
        when(provider.listItems(EMAIL)).thenReturn(List.of(ConnectionResponse.of(
                UUID.randomUUID(), ITEM_ID, 612L, "Nubank",
                OffsetDateTime.parse("2026-08-15T12:00:00Z"),
                OffsetDateTime.parse("2026-08-15T13:00:00Z"))));

        webTestClient.get()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].itemId").isEqualTo(ITEM_ID)
                .jsonPath("$[0].institution").isEqualTo("Nubank")
                .jsonPath("$[0].lastSyncedAt").isNotEmpty();
    }

    @Test
    @DisplayName("DELETE /items/{id} - Desvincula e responde 204; conexão alheia responde 404")
    void unlinkDelegaERespeitaDono() {
        UUID meu = UUID.randomUUID();
        UUID alheio = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Item não encontrado")).when(provider).unlinkItem(EMAIL, alheio);

        webTestClient.delete()
                .uri("/api/v1/connectors/items/" + meu)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();
        verify(provider).unlinkItem(EMAIL, meu);

        webTestClient.delete()
                .uri("/api/v1/connectors/items/" + alheio)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /sync - Mesmo shape do legado, tipado, com itemsSynced; ?days= é repassado")
    void syncDevolveResultadoTipado() {
        UUID uploadId = UUID.randomUUID();
        when(provider.sync(EMAIL, 30)).thenReturn(new OpenFinanceProvider.SyncResult(
                new BankStatementService.ImportResult(uploadId, 12, 7, 2, 3, false, "PLUGGY"), 2));

        webTestClient.post()
                .uri("/api/v1/connectors/sync?days=30")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.uploadId").isEqualTo(uploadId.toString())
                .jsonPath("$.transactionsImported").isEqualTo(12)
                .jsonPath("$.suggested").isEqualTo(7)
                .jsonPath("$.uncategorized").isEqualTo(2)
                .jsonPath("$.reconciled").isEqualTo(3)
                .jsonPath("$.format").isEqualTo("PLUGGY")
                .jsonPath("$.itemsSynced").isEqualTo(2);
    }

    @Test
    @DisplayName("POST /sync - Sem ?days= usa 90; janela inválida vira 400 com a orientação do serviço")
    void syncUsaJanelaPadraoEPropaga400() {
        when(provider.sync(EMAIL, 90)).thenThrow(new IllegalArgumentException(
                "Nenhuma conexão registrada — conecte uma instituição pelo app antes de sincronizar"));

        webTestClient.post()
                .uri("/api/v1/connectors/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail)
                                .contains("Nenhuma conexão"));

        verify(provider).sync(EMAIL, 90);
        verify(provider, never()).sync(anyString(), org.mockito.ArgumentMatchers.eq(0));
        verify(provider, never()).registerItem(any(), any());
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
