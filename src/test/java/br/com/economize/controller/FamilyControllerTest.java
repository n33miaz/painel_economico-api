package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.family.FamilyAnalyticsResponse;
import br.com.economize.dto.family.FamilyRequests;
import br.com.economize.dto.family.FamilyResponses;
import br.com.economize.dto.family.FamilyTransactionResponse;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.family.FamilyAnalyticsService;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.family.FamilyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As rotas da casa (EC-149): contrato da §7 — códigos de status, formato do
 * corpo e o que cada erro do serviço vira em HTTP. O serviço é dublado; as
 * regras dele têm teste próprio.
 */
@WebFluxTest(FamilyController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
@DisplayName("FamilyController (EC-149)")
class FamilyControllerTest {

    private static final String EMAIL = "ana@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private FamilyService familyService;

    @MockitoBean
    private FamilyAnalyticsService familyAnalyticsService;

    @MockitoBean
    private FamilyTransferService familyTransferService;

    private final UUID groupId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    // ------------------------------------------------------------ casa

    @Test
    @DisplayName("GET /family - devolve a casa com membros, mySharing e convite sem código")
    void getReturnsTheFamily() {
        when(familyService.get(EMAIL)).thenReturn(familyResponse());

        webTestClient.get().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(groupId.toString())
                .jsonPath("$.name").isEqualTo("Casa")
                .jsonPath("$.role").isEqualTo("OWNER")
                .jsonPath("$.members[0].isMe").isEqualTo(true)
                .jsonPath("$.members[0].shareScope").isEqualTo("TOTALS")
                .jsonPath("$.mySharing.includeUnassigned").isEqualTo(true)
                .jsonPath("$.mySharing.hiddenCategoryIds").isArray()
                .jsonPath("$.invite.code").isEmpty()
                .jsonPath("$.invite.expiresAt").exists();
    }

    @Test
    @DisplayName("GET /family - sem casa responde 404 ProblemDetail nao-encontrado")
    void getWithoutFamilyIs404() {
        when(familyService.get(EMAIL)).thenThrow(new ResourceNotFoundException("Você ainda não faz parte de uma casa"));

        webTestClient.get().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://economize.app/erros/nao-encontrado")
                .jsonPath("$.detail").isEqualTo("Você ainda não faz parte de uma casa");
    }

    @Test
    @DisplayName("POST /family - cria com 201, com ou sem corpo")
    void createReturns201() {
        when(familyService.create(eq(EMAIL), any())).thenReturn(familyResponse());

        webTestClient.post().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Casa\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.role").isEqualTo("OWNER");

        webTestClient.post().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST /family - já tendo casa responde 409")
    void createConflictIs409() {
        when(familyService.create(eq(EMAIL), any()))
                .thenThrow(new ResourceConflictException("Você já faz parte de uma casa"));

        webTestClient.post().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://economize.app/erros/conflito");
    }

    @Test
    @DisplayName("PATCH /family - renomeia; nome em branco é 400 de validação")
    void renameValidatesName() {
        when(familyService.rename(eq(EMAIL), any())).thenReturn(familyResponse());

        webTestClient.patch().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Lar\"}")
                .exchange()
                .expectStatus().isOk();

        webTestClient.patch().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"  \"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida");
    }

    @Test
    @DisplayName("DELETE /family - 204; MEMBER tentando é 400 com a regra")
    void deleteFamily() {
        webTestClient.delete().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();
        verify(familyService).delete(EMAIL);

        doThrow(new IllegalArgumentException("Só quem criou a casa pode desfazer a casa"))
                .when(familyService).delete(EMAIL);
        webTestClient.delete().uri("/api/v1/family")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Só quem criou a casa pode desfazer a casa");
    }

    // ------------------------------------------------------------ convite

    @Test
    @DisplayName("POST /family/invites - 201 com o código e a validade")
    void issueInviteReturnsTheCode() {
        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        when(familyService.issueInvite(EMAIL)).thenReturn(new FamilyResponses.InviteInfo("ABCD2345", expires));

        webTestClient.post().uri("/api/v1/family/invites")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ABCD2345")
                .jsonPath("$.expiresAt").exists();
    }

    @Test
    @DisplayName("POST /family/join - 200 com a casa; código inválido é 404; em branco é 400")
    void joinContract() {
        when(familyService.join(eq(EMAIL), any())).thenReturn(familyResponse());

        webTestClient.post().uri("/api/v1/family/join")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"abcd-2345\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(groupId.toString());

        ArgumentCaptor<FamilyRequests.JoinFamily> captor = ArgumentCaptor.forClass(FamilyRequests.JoinFamily.class);
        verify(familyService).join(eq(EMAIL), captor.capture());
        // o controller entrega o código cru; normalizar é do serviço
        assertThat(captor.getValue().code()).isEqualTo("abcd-2345");

        when(familyService.join(eq(EMAIL), any()))
                .thenThrow(new ResourceNotFoundException("Convite inválido ou expirado"));
        webTestClient.post().uri("/api/v1/family/join")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"ZZZZ9999\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Convite inválido ou expirado");

        webTestClient.post().uri("/api/v1/family/join")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ------------------------------------------------------------ membros / sharing

    @Test
    @DisplayName("DELETE /family/members/{id} - 204, e 'me' passa como está")
    void removeMemberPassesTheRawId() {
        webTestClient.delete().uri("/api/v1/family/members/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();
        verify(familyService).removeMember(EMAIL, "me");

        webTestClient.delete().uri("/api/v1/family/members/" + memberId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();
        verify(familyService).removeMember(EMAIL, memberId.toString());
    }

    @Test
    @DisplayName("PUT /family/sharing - devolve mySharing; escopo em branco é 400; conta alheia é 400")
    void updateSharingContract() {
        UUID hidden = UUID.randomUUID();
        when(familyService.updateSharing(eq(EMAIL), any()))
                .thenReturn(new FamilyResponses.SharingSettings("TRANSACTIONS", List.of(hidden), List.of(), false));

        webTestClient.put().uri("/api/v1/family/sharing")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"shareScope\":\"TRANSACTIONS\",\"hiddenCategoryIds\":[\"" + hidden
                        + "\"],\"sharedAccountIds\":[],\"includeUnassigned\":false}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.shareScope").isEqualTo("TRANSACTIONS")
                .jsonPath("$.hiddenCategoryIds[0]").isEqualTo(hidden.toString())
                .jsonPath("$.includeUnassigned").isEqualTo(false);

        webTestClient.put().uri("/api/v1/family/sharing")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"shareScope\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest();

        when(familyService.updateSharing(eq(EMAIL), any()))
                .thenThrow(new IllegalArgumentException("Conta x não existe ou não é sua"));
        webTestClient.put().uri("/api/v1/family/sharing")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"shareScope\":\"TOTALS\",\"sharedAccountIds\":[\"" + UUID.randomUUID() + "\"]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Conta x não existe ou não é sua");
    }

    // ------------------------------------------------------------ visão da casa

    @Test
    @DisplayName("GET /family/analytics/monthly - month vira janela; NONE sai com totals nulo")
    void monthlyAnalytics() {
        when(familyAnalyticsService.monthly(eq(EMAIL), any(AnalysisWindow.class))).thenReturn(analyticsResponse());

        webTestClient.get().uri("/api/v1/family/analytics/monthly?month=2026-07")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.window.month").isEqualTo("2026-07")
                .jsonPath("$.members[0].isMe").isEqualTo(true)
                .jsonPath("$.members[0].totals.net").isEqualTo(4700.00)
                .jsonPath("$.members[1].shareScope").isEqualTo("NONE")
                .jsonPath("$.members[1].totals").isEmpty()
                .jsonPath("$.members[1].categories").isEmpty()
                .jsonPath("$.combined.expense").isEqualTo(300.00)
                .jsonPath("$.combined.categories[0].categoryName").isEqualTo("Alimentação");

        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(familyAnalyticsService).monthly(eq(EMAIL), captor.capture());
        assertThat(captor.getValue().monthLabel()).isEqualTo("2026-07");
    }

    @Test
    @DisplayName("GET /family/analytics/monthly - sem parâmetro usa o mês corrente; janela torta é 400")
    void monthlyWindowRules() {
        when(familyAnalyticsService.monthly(eq(EMAIL), any(AnalysisWindow.class))).thenReturn(analyticsResponse());

        webTestClient.get().uri("/api/v1/family/analytics/monthly")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();
        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(familyAnalyticsService).monthly(eq(EMAIL), captor.capture());
        assertThat(captor.getValue().monthLabel()).isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());

        webTestClient.get().uri("/api/v1/family/analytics/monthly?month=2026-07&start=2026-07-12&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("nunca os dois"));
    }

    @Test
    @DisplayName("GET /family/transactions - linhas com o selo do membro; filtros passam adiante")
    void transactionsCarryTheMember() {
        BankTransaction tx = BankTransaction.builder()
                .id(UUID.randomUUID()).transactionId("ext-1").type("DEBIT")
                .amount(new BigDecimal("-42.00")).description("Mercado")
                .date(OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED).build();
        UUID categoryId = UUID.randomUUID();
        when(familyAnalyticsService.transactions(eq(EMAIL), any(AnalysisWindow.class), eq(memberId), eq(categoryId)))
                .thenReturn(List.of(FamilyTransactionResponse.from(tx, memberId, "Bia")));

        webTestClient.get().uri("/api/v1/family/transactions?start=2026-07-01&end=2026-07-31&memberId="
                        + memberId + "&categoryId=" + categoryId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].description").isEqualTo("Mercado")
                .jsonPath("$[0].amount").isEqualTo(-42.00)
                .jsonPath("$[0].memberId").isEqualTo(memberId.toString())
                .jsonPath("$[0].memberName").isEqualTo("Bia")
                .jsonPath("$[0].internalTransfer").isEqualTo(false);

        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(familyAnalyticsService).transactions(eq(EMAIL), captor.capture(), eq(memberId), eq(categoryId));
        assertThat(captor.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(captor.getValue().monthLabel()).isNull();
    }

    @Test
    @DisplayName("GET /family/transactions - sem casa propaga 404; memberId torto é 400")
    void transactionsErrors() {
        when(familyAnalyticsService.transactions(eq(EMAIL), any(AnalysisWindow.class), isNull(), isNull()))
                .thenThrow(new ResourceNotFoundException("Você ainda não faz parte de uma casa"));

        webTestClient.get().uri("/api/v1/family/transactions?month=2026-07")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri("/api/v1/family/transactions?month=2026-07&memberId=nao-e-uuid")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Toda rota exige token")
    void everyRouteRequiresAuthentication() {
        webTestClient.get().uri("/api/v1/family").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/family").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/family/join")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"code\":\"ABCD2345\"}")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/family/analytics/monthly").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/family/transactions").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(familyService, familyAnalyticsService);
    }

    // ------------------------------------------------------------ apoio

    private FamilyResponses.FamilyResponse familyResponse() {
        return new FamilyResponses.FamilyResponse(
                groupId, "Casa", "OWNER",
                List.of(new FamilyResponses.MemberItem(memberId, userId, "Ana", "OWNER",
                        OffsetDateTime.now(ZoneOffset.UTC), "TOTALS", true)),
                new FamilyResponses.SharingSettings("TOTALS", List.of(), List.of(), true),
                new FamilyResponses.InviteInfo(null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(7)));
    }

    private FamilyAnalyticsResponse analyticsResponse() {
        FamilyAnalyticsResponse.CategorySlice food = new FamilyAnalyticsResponse.CategorySlice(
                UUID.randomUUID(), "Alimentação", BigDecimal.ZERO, new BigDecimal("300.00"), 3);
        return new FamilyAnalyticsResponse(
                new FamilyAnalyticsResponse.Window(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "2026-07"),
                List.of(
                        new FamilyAnalyticsResponse.MemberAnalytics(memberId, "Ana", true, "TOTALS",
                                new FamilyAnalyticsResponse.Totals(new BigDecimal("5000.00"),
                                        new BigDecimal("300.00"), new BigDecimal("4700.00")),
                                List.of(food)),
                        new FamilyAnalyticsResponse.MemberAnalytics(UUID.randomUUID(), "Caio", false, "NONE",
                                null, List.of())),
                new FamilyAnalyticsResponse.Combined(new BigDecimal("5000.00"), new BigDecimal("300.00"),
                        new BigDecimal("4700.00"), List.of(food)));
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    @Test
    @DisplayName("POST /family/reconcile-transfers - devolve o que saiu da soma da casa")
    void reconcileTransfersReportsWhatLeftTheHouse() {
        when(familyTransferService.reconcile(EMAIL))
                .thenReturn(new FamilyTransferService.Outcome(1755, 2, 1));

        webTestClient.post()
                .uri("/api/v1/family/reconcile-transfers")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.scanned").isEqualTo(1755)
                .jsonPath("$.marked").isEqualTo(2)
                // against = 0 explicaria um zero por falta de com quem comparar
                .jsonPath("$.against").isEqualTo(1);
    }
}
