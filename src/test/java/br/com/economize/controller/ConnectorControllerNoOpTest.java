package br.com.economize.controller;

import br.com.economize.config.ConnectorProviderConfig;
import br.com.economize.config.CorsConfig;
import br.com.economize.dto.connector.RegisterConnectionRequest;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.connector.NoOpOpenFinanceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

/**
 * A rota neutra numa instalação SEM conector: é o {@link NoOpOpenFinanceProvider}
 * que responde. O status diz {@code enabled=false} (o app esconde a seção) e
 * qualquer operação responde 503 — a instalação é que não atende, não o
 * pedido que está errado.
 */
@WebFluxTest(ConnectorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        ConnectorProviderConfig.class })
class ConnectorControllerNoOpTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("GET /status - Sem conector: enabled=false, nome neutro e widget nulo")
    void statusDesligado() {
        webTestClient.get()
                .uri("/api/v1/connectors/status")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.configured").isEqualTo(false)
                .jsonPath("$.itemCount").isEqualTo(0)
                .jsonPath("$.provider.id").isEqualTo("none")
                .jsonPath("$.provider.displayName").isEqualTo("Open Finance")
                .jsonPath("$.widget").isEmpty();
    }

    @Test
    @DisplayName("Operações sem conector respondem 503 Serviço Indisponível, nunca 400 nem 500")
    void operacoesRespondem503() {
        webTestClient.post()
                .uri("/api/v1/connectors/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Serviço Indisponível")
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail)
                                .doesNotContainIgnoringCase("pluggy"));

        webTestClient.post()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterConnectionRequest(UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isEqualTo(503);

        webTestClient.get()
                .uri("/api/v1/connectors/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(503);

        webTestClient.delete()
                .uri("/api/v1/connectors/items/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(503);

        webTestClient.post()
                .uri("/api/v1/connectors/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
