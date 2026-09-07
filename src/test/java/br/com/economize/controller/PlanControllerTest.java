package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.plan.PlanInterestRequest;
import br.com.economize.dto.plan.PlansResponse;
import br.com.economize.model.Plan;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.PlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(PlanController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class PlanControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private PlanService planService;

    @Test
    @DisplayName("GET /plans - Oferta com o plano vigente, os dois planos, sem checkout e o interesse já dado")
    void plansDevolveAOferta() {
        when(planService.describe(EMAIL)).thenReturn(new PlansResponse(
                Plan.FREE,
                List.of(
                        new PlansResponse.PlanOption(Plan.FREE, "Gratuito", BigDecimal.ZERO, List.of("Com anúncios")),
                        new PlansResponse.PlanOption(Plan.PLUS, "Economize! Plus", new BigDecimal("9.90"),
                                List.of("Sem anúncios", "Conexão bancária ilimitada", "Relatórios em PDF",
                                        "Prioridade no assistente"))),
                false,
                true));

        webTestClient.get()
                .uri("/api/v1/plans")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.current").isEqualTo("FREE")
                .jsonPath("$.plans.length()").isEqualTo(2)
                .jsonPath("$.plans[0].id").isEqualTo("FREE")
                .jsonPath("$.plans[0].name").isEqualTo("Gratuito")
                .jsonPath("$.plans[0].priceMonthly").isEqualTo(0)
                .jsonPath("$.plans[1].id").isEqualTo("PLUS")
                .jsonPath("$.plans[1].name").isEqualTo("Economize! Plus")
                .jsonPath("$.plans[1].priceMonthly").value(price ->
                        assertThat(((Number) price).doubleValue()).isEqualTo(9.9))
                .jsonPath("$.plans[1].features[0]").isEqualTo("Sem anúncios")
                .jsonPath("$.plans[1].features.length()").isEqualTo(4)
                .jsonPath("$.checkoutAvailable").isEqualTo(false)
                .jsonPath("$.interestRegistered").isEqualTo(true);
    }

    @Test
    @DisplayName("GET /plans - Sem token deve retornar 401")
    void plansExigeToken() {
        webTestClient.get()
                .uri("/api/v1/plans")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(planService, never()).describe(anyString());
    }

    @Test
    @DisplayName("POST /plans/interest - Registra e responde 204 sem corpo")
    void interestRegistraERespondeNoContent() {
        webTestClient.post()
                .uri("/api/v1/plans/interest")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlanInterestRequest(Plan.PLUS))
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(planService).registerInterest(EMAIL, Plan.PLUS);
    }

    @Test
    @DisplayName("POST /plans/interest - Plano fora do enum responde 400 sem chamar o serviço")
    void interestRejeitaPlanoDesconhecido() {
        webTestClient.post()
                .uri("/api/v1/plans/interest")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"plan\":\"GOLD\"}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(planService, never()).registerInterest(anyString(), any());
    }

    @Test
    @DisplayName("POST /plans/interest - Sem plano responde 400 sem chamar o serviço")
    void interestExigePlano() {
        webTestClient.post()
                .uri("/api/v1/plans/interest")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(planService, never()).registerInterest(anyString(), any());
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
