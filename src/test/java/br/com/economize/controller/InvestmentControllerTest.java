package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.investment.InvestmentMovementService;
import br.com.economize.service.investment.InvestmentProfileService;
import br.com.economize.service.investment.InvestmentService;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As rotas de investimentos: corpo validado, status de criação, o 404 do id
 * alheio (nunca 403), o 503 do conector desligado e a amarração ao dono do
 * token.
 */
@WebFluxTest(InvestmentController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
@DisplayName("InvestmentController — as rotas de investimentos")
class InvestmentControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private InvestmentService investmentService;

    @MockitoBean
    private InvestmentMovementService movementService;

    @MockitoBean
    private InvestmentProfileService profileService;

    private String bearer() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    private static InvestmentResponses.PositionItem posicao(String nome, String code, BigDecimal currentValue) {
        return new InvestmentResponses.PositionItem(UUID.randomUUID(), "MANUAL", null, null, "Avenue", nome, code,
                "ETF", "ETFs", "ETF", "USD", null, "USD", new BigDecimal("12"), null, new BigDecimal("6000"),
                currentValue, null, null, null, false, currentValue == null, true,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("GET /summary devolve o painel com needsQuote e movements12m")
    void resumo() {
        when(investmentService.summary(EMAIL)).thenReturn(new InvestmentResponses.Summary(
                new BigDecimal("8000"), new BigDecimal("3200"), new BigDecimal("200"), new BigDecimal("6.67"),
                3, 2,
                List.of(new InvestmentResponses.TypeSlice("TREASURY", "Tesouro Direto", new BigDecimal("2100"), new BigDecimal("0.6563"))),
                List.of(new InvestmentResponses.InstitutionSlice("Banco Inter", new BigDecimal("3200"), BigDecimal.ONE)),
                List.of(new InvestmentResponses.IndexerSlice("SELIC", new BigDecimal("2100"), new BigDecimal("0.6563"))),
                OffsetDateTime.now(), 1, List.of("CONNECTOR", "MANUAL"),
                new InvestmentResponses.MovementTotals12m(new BigDecimal("3000"), new BigDecimal("500"),
                        new BigDecimal("42.10"), new BigDecimal("2500")),
                List.of("VT")));

        webTestClient.get().uri("/api/v1/investments/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalInvested").isEqualTo(8000)
                .jsonPath("$.currentValue").isEqualTo(3200)
                .jsonPath("$.profitPercent").isEqualTo(6.67)
                .jsonPath("$.positionsCount").isEqualTo(3)
                .jsonPath("$.byType[0].label").isEqualTo("Tesouro Direto")
                .jsonPath("$.stalePositions").isEqualTo(1)
                .jsonPath("$.needsQuote[0]").isEqualTo("VT")
                .jsonPath("$.movements12m.net").isEqualTo(2500);
    }

    @Test
    @DisplayName("GET /positions lista com o dono do token; a manual sem cotação vem com currentValue nulo e needsQuote")
    void listaPosicoes() {
        when(investmentService.list(EMAIL)).thenReturn(List.of(posicao("Vanguard Total World", "VT", null)));

        webTestClient.get().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("Vanguard Total World")
                .jsonPath("$[0].code").isEqualTo("VT")
                .jsonPath("$[0].currentValue").doesNotExist()
                .jsonPath("$[0].needsQuote").isEqualTo(true)
                .jsonPath("$[0].editable").isEqualTo(true);
    }

    @Test
    @DisplayName("POST /positions cria e responde 201")
    void criaPosicao() {
        when(investmentService.create(eq(EMAIL), any(InvestmentRequests.CreatePosition.class)))
                .thenReturn(posicao("Vanguard Total World", "VT", null));

        webTestClient.post().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Vanguard Total World", "type", "ETF", "code", "vt", "quantity", 12))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.code").isEqualTo("VT");
    }

    @Test
    @DisplayName("Sem nome ou sem tipo é 400, e o serviço nem é chamado; quantidade negativa também")
    void validaCadastro() {
        webTestClient.post().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "ETF"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail).contains("name"));

        webTestClient.post().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "VT"))
                .exchange()
                .expectStatus().isBadRequest();

        Map<String, Object> negativa = new HashMap<>();
        negativa.put("name", "VT");
        negativa.put("type", "ETF");
        negativa.put("quantity", -1);
        webTestClient.post().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(negativa)
                .exchange()
                .expectStatus().isBadRequest();

        verify(investmentService, never()).create(any(), any());
    }

    @Test
    @DisplayName("Tipo desconhecido chega ao serviço como texto e volta 400 com a lista aceita — nunca 500")
    void tipoInvalidoE400() {
        when(investmentService.create(eq(EMAIL), any()))
                .thenThrow(new IllegalArgumentException("Tipo inválido: use FIXED_INCOME, TREASURY, FUND, EQUITY, ETF, CRYPTO, PENSION, OTHER"));

        webTestClient.post().uri("/api/v1/investments/positions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "X", "type", "acao"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail).contains("FIXED_INCOME"));
    }

    @Test
    @DisplayName("PATCH e DELETE em id de outro usuário respondem 404 — nunca 403")
    void idAlheioE404() {
        UUID alheio = UUID.randomUUID();
        when(investmentService.update(eq(EMAIL), eq(alheio), any()))
                .thenThrow(new ResourceNotFoundException("Posição não encontrada"));
        doThrow(new ResourceNotFoundException("Posição não encontrada"))
                .when(investmentService).delete(EMAIL, alheio);

        webTestClient.patch().uri("/api/v1/investments/positions/" + alheio)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "outro"))
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.delete().uri("/api/v1/investments/positions/" + alheio)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("DELETE responde 204 e não devolve corpo")
    void apagaPosicao() {
        UUID id = UUID.randomUUID();

        webTestClient.delete().uri("/api/v1/investments/positions/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(investmentService).delete(EMAIL, id);
    }

    @Test
    @DisplayName("GET /movements repassa ?months= e, sem ele, deixa o default para o serviço")
    void movimentos() {
        InvestmentResponses.Movements vazio = new InvestmentResponses.Movements(6,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 6), List.of(
                new InvestmentResponses.MovementRow(UUID.randomUUID(), LocalDate.of(2026, 8, 10), "APPLY",
                        new BigDecimal("-1000.00"), "Aplicação CDB", "Banco Inter", UUID.randomUUID())),
                new InvestmentResponses.MovementTotals(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new BigDecimal("1000"));
        when(movementService.movements(eq(EMAIL), eq(6))).thenReturn(vazio);
        when(movementService.movements(eq(EMAIL), isNull())).thenReturn(vazio);

        webTestClient.get().uri("/api/v1/investments/movements?months=6")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.months").isEqualTo(6)
                .jsonPath("$.movements[0].kind").isEqualTo("APPLY")
                .jsonPath("$.movements[0].amount").isEqualTo(-1000.00)
                .jsonPath("$.totals.applied").isEqualTo(1000)
                .jsonPath("$.netInvested").isEqualTo(1000);

        webTestClient.get().uri("/api/v1/investments/movements")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk();

        verify(movementService).movements(EMAIL, 6);
        verify(movementService).movements(EMAIL, null);
    }

    @Test
    @DisplayName("POST /sync com o conector desligado responde 503 e orienta a flag")
    void syncSemConectorE503() {
        when(investmentService.sync(EMAIL)).thenThrow(new ServiceUnavailableException(
                "Conector Pluggy desativado nesta instalação — defina PLUGGY_ENABLED=true"));

        webTestClient.post().uri("/api/v1/investments/sync")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Serviço Indisponível")
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail).contains("PLUGGY_ENABLED"));
    }

    @Test
    @DisplayName("POST /sync devolve o que a sincronização fez")
    void sync() {
        when(investmentService.sync(EMAIL)).thenReturn(new InvestmentResponses.SyncResult(3, 2, 1, 2, 0, 0));

        webTestClient.post().uri("/api/v1/investments/sync")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.synced").isEqualTo(3)
                .jsonPath("$.created").isEqualTo(2)
                .jsonPath("$.updated").isEqualTo(1)
                .jsonPath("$.itemsRead").isEqualTo(2)
                .jsonPath("$.skippedItems").isEqualTo(0);
    }

    @Test
    @DisplayName("GET /profile serializa watch, topics, derivedFrom e isDefault com esses nomes")
    void perfil() {
        when(profileService.profile(EMAIL)).thenReturn(new InvestmentResponses.Profile(
                List.of("CDI", "USD"),
                List.of(new InvestmentResponses.WatchItem("RATE", "CDI", null, "DERIVED"),
                        new InvestmentResponses.WatchItem("TICKER", "VT", "US", "MANUAL")),
                List.of("renda-fixa", "selic-cdi", "macro-br"),
                new InvestmentResponses.DerivedFrom(List.of("CDB Inter → CDI"), List.of(), List.of("TICKER VT (US)"), null),
                false));

        webTestClient.get().uri("/api/v1/investments/profile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.indexers[0]").isEqualTo("CDI")
                .jsonPath("$.watch[1].kind").isEqualTo("TICKER")
                .jsonPath("$.watch[1].market").isEqualTo("US")
                .jsonPath("$.watch[1].source").isEqualTo("MANUAL")
                .jsonPath("$.topics[2]").isEqualTo("macro-br")
                .jsonPath("$.derivedFrom.positions[0]").isEqualTo("CDB Inter → CDI")
                .jsonPath("$.isDefault").isEqualTo(false);
    }

    @Test
    @DisplayName("POST /interests cria (201) e DELETE /interests/{kind}/{code} remove (204) pelo dono do token")
    void interesses() {
        when(profileService.addInterest(eq(EMAIL), any(InvestmentRequests.CreateInterest.class)))
                .thenReturn(new InvestmentResponses.InterestItem(UUID.randomUUID(), "TICKER", "VT", "US", OffsetDateTime.now()));

        webTestClient.post().uri("/api/v1/investments/interests")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "TICKER", "code", "VT", "market", "US"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.code").isEqualTo("VT");

        // sem código é 400 antes de chegar ao serviço
        webTestClient.post().uri("/api/v1/investments/interests")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "TICKER"))
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.delete().uri("/api/v1/investments/interests/RATE/CDI")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNoContent();

        verify(profileService).removeInterest(EMAIL, "RATE", "CDI");
    }

    @Test
    @DisplayName("Interesse inexistente responde 404")
    void interesseInexistente() {
        doThrow(new ResourceNotFoundException("Interesse não encontrado"))
                .when(profileService).removeInterest(EMAIL, "TOPIC", "cripto");

        webTestClient.delete().uri("/api/v1/investments/interests/TOPIC/cripto")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Sem token, nenhuma rota de investimentos responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/investments/summary")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(investmentService, never()).summary(any());
    }
}
