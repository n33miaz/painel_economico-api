package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.account.AccountResponse;
import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.exception.GlobalExceptionHandler;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.CardInvoiceService;
import br.com.economize.service.InvoiceReserveService;
import br.com.economize.service.ConnectorAccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(AccountController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        GlobalExceptionHandler.class})
class AccountControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private ConnectorAccountService accountService;

    @MockitoBean
    private CardInvoiceService cardInvoiceService;

    @MockitoBean
    private InvoiceReserveService invoiceReserveService;

    @Test
    @DisplayName("GET /accounts - lista as origens com nome, tipo e metadados de fatura")
    void listReturnsAccounts() {
        UUID id = UUID.randomUUID();
        when(accountService.list(EMAIL)).thenReturn(List.of(new AccountResponse(
                id, "Ultravioleta ····1234", ConnectorAccount.AccountType.CREDIT_CARD,
                "Nubank", 10, 17, true)));

        webTestClient.get()
                .uri("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(id.toString())
                .jsonPath("$[0].name").isEqualTo("Ultravioleta ····1234")
                .jsonPath("$[0].type").isEqualTo("CREDIT_CARD")
                .jsonPath("$[0].institution").isEqualTo("Nubank")
                .jsonPath("$[0].statementClosingDay").isEqualTo(10)
                .jsonPath("$[0].linked").isEqualTo(true);
    }

    @Test
    @DisplayName("GET /accounts sem token responde 401 — rota nova nasce autenticada")
    void listRequiresAuthentication() {
        webTestClient.get()
                .uri("/api/v1/accounts")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("GET /accounts/{id}/invoices - devolve os ciclos e declara que a fatura foi derivada")
    void invoicesReturnsCycles() {
        UUID id = UUID.randomUUID();
        when(cardInvoiceService.invoices(EMAIL, id, 6)).thenReturn(new CardInvoicesResponse(
                id, "Ultravioleta ····1234", ConnectorAccount.AccountType.CREDIT_CARD, "Nubank",
                CardInvoicesResponse.CycleSource.PROVIDER_CLOSING_DAY,
                List.of(new CardInvoicesResponse.Invoice(
                        "2026-08", LocalDate.of(2026, 7, 11), LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17),
                        new BigDecimal("1234.56"), new BigDecimal("1334.56"), new BigDecimal("100.00"),
                        new BigDecimal("900.00"), 12, false,
                        new CardInvoicesResponse.Reserve(UUID.randomUUID(), new BigDecimal("641.14"),
                                null, "Mercado Pago ····7340", "deixei separado"),
                        List.of()))));

        webTestClient.get()
                .uri("/api/v1/accounts/" + id + "/invoices")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cycleSource").isEqualTo("PROVIDER_CLOSING_DAY")
                .jsonPath("$.invoices[0].reference").isEqualTo("2026-08")
                .jsonPath("$.invoices[0].periodStart").isEqualTo("2026-07-11")
                .jsonPath("$.invoices[0].closingDate").isEqualTo("2026-08-10")
                .jsonPath("$.invoices[0].dueDate").isEqualTo("2026-08-17")
                .jsonPath("$.invoices[0].total").isEqualTo(1234.56)
                .jsonPath("$.invoices[0].purchasesTotal").isEqualTo(1334.56)
                .jsonPath("$.invoices[0].refundsTotal").isEqualTo(100.00)
                .jsonPath("$.invoices[0].paymentsTotal").isEqualTo(900.00)
                .jsonPath("$.invoices[0].open").isEqualTo(false)
                // EC-181: a reserva viaja DENTRO da fatura que ela cobre
                .jsonPath("$.invoices[0].reserve.amount").isEqualTo(641.14)
                .jsonPath("$.invoices[0].reserve.heldInAccountName")
                .isEqualTo("Mercado Pago ····7340");

        // o default da janela é 6 ciclos
        verify(cardInvoiceService).invoices(EMAIL, id, 6);
    }

    @Test
    @DisplayName("cartão de outro usuário responde 404 com ProblemDetail, nunca 403")
    void invoicesOfOtherUserAreNotFound() {
        UUID id = UUID.randomUUID();
        when(cardInvoiceService.invoices(eq(EMAIL), eq(id), eq(6)))
                .thenThrow(new ResourceNotFoundException("Conta não encontrada"));

        webTestClient.get()
                .uri("/api/v1/accounts/" + id + "/invoices")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");
    }

    @Test
    @DisplayName("months fora da faixa responde 400 com ProblemDetail dizendo o limite")
    void invoicesRejectOutOfRangeMonths() {
        UUID id = UUID.randomUUID();
        when(cardInvoiceService.invoices(EMAIL, id, 99))
                .thenThrow(new IllegalArgumentException("Janela inválida: months deve estar entre 1 e 24 (recebido: 99)"));

        webTestClient.get()
                .uri("/api/v1/accounts/" + id + "/invoices?months=99")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("entre 1 e 24"));
    }

    @Test
    @DisplayName("PUT reserve - separa o valor da fatura e devolve onde ele está")
    void saveReserveReturnsWhereTheMoneyIs() {
        UUID id = UUID.randomUUID();
        UUID cofre = UUID.randomUUID();
        UUID reservaId = UUID.randomUUID();
        when(invoiceReserveService.save(eq(EMAIL), eq(id), eq("2026-09"),
                eq(new BigDecimal("641.14")), eq(cofre), eq("deixei separado")))
                .thenReturn(new CardInvoicesResponse.Reserve(reservaId, new BigDecimal("641.14"),
                        cofre, "Mercado Pago ····7340", "deixei separado"));

        webTestClient.put()
                .uri("/api/v1/accounts/" + id + "/invoices/2026-09/reserve")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .bodyValue(java.util.Map.of(
                        "amount", "641.14",
                        "heldInAccountId", cofre.toString(),
                        "note", "deixei separado"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.amount").isEqualTo(641.14)
                .jsonPath("$.heldInAccountName").isEqualTo("Mercado Pago ····7340");
    }

    @Test
    @DisplayName("PUT reserve - valor não positivo é barrado na validação, sem chegar ao serviço")
    void saveReserveRejectsNonPositiveAmount() {
        UUID id = UUID.randomUUID();

        webTestClient.put()
                .uri("/api/v1/accounts/" + id + "/invoices/2026-09/reserve")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .bodyValue(java.util.Map.of("amount", "0"))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(invoiceReserveService);
    }

    @Test
    @DisplayName("DELETE reserve - responde 204 mesmo quando não havia reserva")
    void deleteReserveIsIdempotent() {
        UUID id = UUID.randomUUID();

        webTestClient.delete()
                .uri("/api/v1/accounts/" + id + "/invoices/2026-09/reserve")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();

        verify(invoiceReserveService).delete(EMAIL, id, "2026-09");
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
