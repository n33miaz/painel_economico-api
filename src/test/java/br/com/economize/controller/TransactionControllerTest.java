package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.statement.UpdateTransactionAliasRequest;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.DuplicateTransactionService;
import br.com.economize.service.InternalTransferService;
import br.com.economize.service.StatementHygieneService;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.TransactionAliasService;
import br.com.economize.service.TransactionReviewService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(TransactionController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class TransactionControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private TransactionReviewService reviewService;

    @MockitoBean
    private TransactionAliasService aliasService;

    @MockitoBean
    private InternalTransferService internalTransferService;

    @MockitoBean
    private FamilyTransferService familyTransferService;

    @MockitoBean
    private StatementHygieneService hygieneService;

    @MockitoBean
    private DuplicateTransactionService duplicateService;

    @Test
    @DisplayName("GET /review/count - só a contagem, sem baixar a fila")
    void reviewCountReturnsOnlyTheNumber() {
        when(reviewService.pendingCount(EMAIL)).thenReturn(1656L);

        webTestClient.get()
                .uri("/api/v1/transactions/review/count")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(1656);

        // a fila agrupada não é consultada: é o ganho inteiro do endpoint
        verify(reviewService, never()).reviewQueue(any(), any());
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Apelido vira a descrição exibida e o nome do banco continua no payload")
    void updateAliasReturnsBothNames() {
        UUID id = UUID.randomUUID();
        BankTransaction renamed = transaction(id);
        renamed.setDisplayAlias("Academia");
        when(aliasService.rename(EMAIL, id, "Academia")).thenReturn(renamed);

        webTestClient.patch()
                .uri("/api/v1/transactions/" + id + "/alias")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest("Academia"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.description").isEqualTo("Academia")
                .jsonPath("$.originalDescription").isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA")
                .jsonPath("$.displayAlias").isEqualTo("Academia")
                // a chave do motor continua saindo do descritivo do banco
                .jsonPath("$.normalizedDescription").isEqualTo("fitmax");

        verify(aliasService).rename(EMAIL, id, "Academia");
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Apelido nulo limpa o campo e devolve o nome do banco")
    void updateAliasWithNullClearsIt() {
        UUID id = UUID.randomUUID();
        when(aliasService.rename(EMAIL, id, null)).thenReturn(transaction(id));

        webTestClient.patch()
                .uri("/api/v1/transactions/" + id + "/alias")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest(null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.description").isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA")
                .jsonPath("$.displayAlias").isEmpty();

        verify(aliasService).rename(EMAIL, id, null);
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Acima de 80 caracteres responde 400 sem chamar o service")
    void updateAliasRejectsOversizedAlias() {
        webTestClient.patch()
                .uri("/api/v1/transactions/" + UUID.randomUUID() + "/alias")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest("a".repeat(UpdateTransactionAliasRequest.MAX_LENGTH + 1)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("80 caracteres"));

        verifyNoInteractions(aliasService);
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Id que não é UUID responde 400, não 500")
    void updateAliasWithMalformedIdIsAClientError() {
        // sem o handler de ServerWebInputException o binding do @PathVariable
        // caía no genérico e virava 500 para um erro que é do cliente
        webTestClient.patch()
                .uri("/api/v1/transactions/nao-e-uuid/alias")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest("Academia"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida");

        verifyNoInteractions(aliasService);
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Transação de outro dono responde 404, não 403")
    void updateAliasOfAnotherOwnerReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(aliasService.rename(EMAIL, id, "Academia"))
                .thenThrow(new ResourceNotFoundException("Transação não encontrada"));

        // 403 confirmaria que o id existe: a rota viraria oráculo de enumeração
        webTestClient.patch()
                .uri("/api/v1/transactions/" + id + "/alias")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest("Academia"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado")
                .jsonPath("$.detail").isEqualTo("Transação não encontrada");
    }

    @Test
    @DisplayName("PATCH /{id}/alias - Sem token responde 401")
    void updateAliasRequiresAuthentication() {
        webTestClient.patch()
                .uri("/api/v1/transactions/" + UUID.randomUUID() + "/alias")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateTransactionAliasRequest("Academia"))
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(aliasService);
    }

    @Test
    @DisplayName("GET - start/end repassam a janela ancorada ao service")
    void listPassesAnchoredWindow() {
        when(reviewService.listTransactions(eq(EMAIL), any(AnalysisWindow.class), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        webTestClient.get()
                .uri("/api/v1/transactions?start=2026-07-12&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(reviewService).listTransactions(eq(EMAIL), captor.capture(), isNull(), isNull(), isNull());
        assertThat(captor.getValue().start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(captor.getValue().end()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(captor.getValue().monthLabel()).isNull();
    }

    @Test
    @DisplayName("GET - month continua funcionando e vira janela do mês")
    void listKeepsTheMonthFilter() {
        when(reviewService.listTransactions(eq(EMAIL), any(AnalysisWindow.class), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        webTestClient.get()
                .uri("/api/v1/transactions?month=2026-07")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(reviewService).listTransactions(eq(EMAIL), captor.capture(), isNull(), isNull(), isNull());
        assertThat(captor.getValue().monthLabel()).isEqualTo("2026-07");
    }

    @Test
    @DisplayName("GET - Sem período nenhum a janela vai nula (histórico inteiro)")
    void listWithoutPeriodPassesNullWindow() {
        when(reviewService.listTransactions(eq(EMAIL), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        webTestClient.get()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].description").isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA")
                .jsonPath("$[0].originalDescription").isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA");

        verify(reviewService).listTransactions(eq(EMAIL), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("GET - month junto com janela responde 400 sem chamar o service")
    void listRejectsMonthAndWindowTogether() {
        webTestClient.get()
                .uri("/api/v1/transactions?month=2026-07&start=2026-07-12&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("nunca os dois"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("GET - Janela pela metade responde 400 sem chamar o service")
    void listRejectsHalfWindow() {
        webTestClient.get()
                .uri("/api/v1/transactions?end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("informados juntos"));

        verifyNoInteractions(reviewService);
    }

    private BankTransaction transaction(UUID id) {
        return BankTransaction.builder()
                .id(id)
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(new BigDecimal("-99.90"))
                .description("PAG*FITMAX 4321 SAO PAULO BRA")
                .normalizedDescription("fitmax")
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .date(OffsetDateTime.parse("2026-08-01T12:00:00Z"))
                .build();
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    @Test
    @DisplayName("PATCH /{id}/family-transfer - a decisão da pessoa numa linha")
    void setFamilyTransferMarksASingleLine() {
        UUID id = UUID.randomUUID();
        BankTransaction linha = BankTransaction.builder()
                .id(id)
                .transactionId("tx-1")
                .type("DEBIT")
                .amount(new BigDecimal("-650.00"))
                .description("Pix enviado - Alice dos Santos Araujo")
                .date(OffsetDateTime.parse("2026-08-07T12:00:00Z"))
                .familyTransfer(true)
                .build();
        when(familyTransferService.setFamilyTransfer(EMAIL, id, true)).thenReturn(linha);

        webTestClient.patch()
                .uri("/api/v1/transactions/" + id + "/family-transfer")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .bodyValue(java.util.Map.of("familyTransfer", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.familyTransfer").isEqualTo(true);
    }

    @Test
    @DisplayName("PATCH /{id}/family-transfer - corpo sem o campo é 400, sem chegar ao serviço")
    void setFamilyTransferRequiresTheFlag() {
        UUID id = UUID.randomUUID();

        webTestClient.patch()
                .uri("/api/v1/transactions/" + id + "/family-transfer")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .bodyValue(java.util.Map.of())
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(familyTransferService);
    }

    @Test
    @DisplayName("POST /tidy - devolve o que cada varredura mexeu")
    void tidyReportsEveryPass() {
        when(hygieneService.runFor(EMAIL))
                .thenReturn(new StatementHygieneService.Outcome(197, 68, 20, 3, 12));

        webTestClient.post()
                .uri("/api/v1/transactions/tidy")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.internalMarked").isEqualTo(197)
                .jsonPath("$.familyMarked").isEqualTo(68)
                .jsonPath("$.duplicatesMarked").isEqualTo(20)
                .jsonPath("$.seriesCreated").isEqualTo(3)
                .jsonPath("$.seriesUpdated").isEqualTo(12);
    }
}
