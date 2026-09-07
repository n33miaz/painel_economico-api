package br.com.economize.controller;

import br.com.economize.config.ConnectorProviderConfig;
import br.com.economize.config.CorsConfig;
import br.com.economize.dto.connector.RegisterPluggyItemRequest;
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
 * Com PLUGGY_ENABLED=false os services do conector nem existem no contexto
 * (@ConditionalOnProperty) e quem responde é o {@link NoOpOpenFinanceProvider}
 * — importado aqui explicitamente porque a fatia web não varre serviços. O
 * contrato do APK publicado se mantém: /status responde "desligado" e o resto
 * orienta a ligar a flag com 400 (a rota neutra responde 503; esta, não).
 */
@WebFluxTest(PluggyConnectorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        ConnectorProviderConfig.class })
class PluggyConnectorFlagOffTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("GET /status - Flag desligada responde enabled=false sem erro (contrato do APK)")
    void statusShouldReportDisabled() {
        webTestClient.get()
                .uri("/api/v1/connectors/pluggy/status")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(false)
                // o APK publicado lê os QUATRO campos: com a flag desligada
                // "owner" também precisa vir, senão some da resposta
                .jsonPath("$.owner").isEqualTo(true)
                .jsonPath("$.configured").isEqualTo(false)
                .jsonPath("$.itemCount").isEqualTo(0);
    }

    @Test
    @DisplayName("POST /sync - Flag desligada responde 400 com orientação")
    void syncShouldFailWhenDisabled() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail)
                                .contains("PLUGGY_ENABLED"));
    }

    @Test
    @DisplayName("POST /connect-token - Flag desligada responde 400")
    void connectTokenShouldFailWhenDisabled() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /items - Flag desligada responde 400")
    void registerShouldFailWhenDisabled() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest(UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("GET /items - Flag desligada responde 400")
    void listShouldFailWhenDisabled() {
        webTestClient.get()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("DELETE /items/{id} - Flag desligada responde 400")
    void unlinkShouldFailWhenDisabled() {
        webTestClient.delete()
                .uri("/api/v1/connectors/pluggy/items/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest();
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
