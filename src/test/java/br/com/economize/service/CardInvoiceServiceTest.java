package br.com.economize.service;

import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A fatura como AGRUPAMENTO (EC-113) — e a prova de que ela não reintroduz o
 * pagamento de fatura como despesa nem como receita (V15/EC-106).
 *
 * <p>As datas são relativas a hoje de propósito: o ciclo em aberto é o que
 * contém a data corrente, e um teste com mês fixo passaria a mentir na virada
 * do mês seguinte.
 */
@ExtendWith(MockitoExtension.class)
class CardInvoiceServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private ConnectorAccountService accountService;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InvoiceReserveService reserveService;

    private CardInvoiceService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        service = new CardInvoiceService(accountService, bankTransactionRepository, userRepository,
                reserveService);
        // sem reserva é o caso comum; os testes do EC-181 sobrescrevem
        lenient().when(reserveService.byReference(any(), any())).thenReturn(Map.of());
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    // ------------------------------------------------- recorte do ciclo

    @Test
    @DisplayName("com dia de fechamento do provedor, o ciclo vai do dia seguinte ao fechamento anterior")
    void shouldCutCycleByProviderClosingDay() {
        ConnectorAccount cartao = card(10, 17);
        owned(cartao);
        // dentro do ciclo que fecha no dia 10 deste mês (começou no dia 11 do
        // mês passado) e um lançamento do ciclo anterior
        LocalDate fechamento = closing(YearMonth.now(ZoneOffset.UTC), 10);
        transactions(cartao,
                tx(cartao, "-100.00", fechamento.minusDays(3)),
                tx(cartao, "-50.00", fechamento.minusDays(1)),
                tx(cartao, "-20.00", fechamento.minusMonths(1).minusDays(2)));

        CardInvoicesResponse response = service.invoices(EMAIL, cartao.getId(), 6);

        assertThat(response.cycleSource())
                .isEqualTo(CardInvoicesResponse.CycleSource.PROVIDER_CLOSING_DAY);
        CardInvoicesResponse.Invoice atual = response.invoices().get(0);
        assertThat(atual.closingDate()).isEqualTo(fechamento);
        assertThat(atual.periodEnd()).isEqualTo(fechamento);
        assertThat(atual.periodStart())
                .isEqualTo(closing(YearMonth.from(fechamento).minusMonths(1), 10).plusDays(1));
        assertThat(atual.transactionCount()).isEqualTo(2);
        assertThat(atual.total()).isEqualByComparingTo("150.00");
        // o lançamento do mês anterior foi para o ciclo anterior, não sumiu
        assertThat(response.invoices()).hasSize(2);
        assertThat(response.invoices().get(1).total()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("sem metadado do provedor a fatura é o MÊS DO CALENDÁRIO — e a resposta declara isso")
    void shouldFallBackToCalendarMonthAndSaySo() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);
        transactions(cartao, tx(cartao, "-80.00", mes.atDay(3)));

        CardInvoicesResponse response = service.invoices(EMAIL, cartao.getId(), 3);

        // o app precisa saber que o recorte é aproximado: nos últimos dias do mês
        // a compra provavelmente cai na fatura seguinte no app do banco
        assertThat(response.cycleSource())
                .isEqualTo(CardInvoicesResponse.CycleSource.CALENDAR_MONTH);
        CardInvoicesResponse.Invoice atual = response.invoices().get(0);
        assertThat(atual.reference()).isEqualTo(mes.toString());
        assertThat(atual.periodStart()).isEqualTo(mes.atDay(1));
        assertThat(atual.periodEnd()).isEqualTo(mes.atEndOfMonth());
        // sem dia de vencimento informado, nada é inventado
        assertThat(atual.dueDate()).isNull();
    }

    @Test
    @DisplayName("vencimento anterior ao fechamento cai no mês SEGUINTE, nunca no passado")
    void dueDateShouldNeverPrecedeClosing() {
        // fecha dia 28, vence dia 5: o dia 5 do mesmo mês já passou
        ConnectorAccount cartao = card(28, 5);
        owned(cartao);
        LocalDate fechamento = closing(YearMonth.now(ZoneOffset.UTC), 28);
        transactions(cartao, tx(cartao, "-10.00", fechamento.minusDays(1)));

        CardInvoicesResponse.Invoice atual = service.invoices(EMAIL, cartao.getId(), 1).invoices().get(0);

        assertThat(atual.dueDate()).isAfter(atual.closingDate());
        assertThat(atual.dueDate().getDayOfMonth()).isEqualTo(5);
    }

    @Test
    @DisplayName("ciclo sem lançamento nenhum é omitido: o usuário não abre mês em branco")
    void emptyCyclesAreOmitted() {
        ConnectorAccount cartao = card(10, 17);
        owned(cartao);
        transactions(cartao, tx(cartao, "-10.00", closing(YearMonth.now(ZoneOffset.UTC), 10).minusDays(2)));

        assertThat(service.invoices(EMAIL, cartao.getId(), 12).invoices()).hasSize(1);
    }

    @Test
    @DisplayName("o ciclo que contém hoje vem marcado como aberto")
    void currentCycleIsOpen() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);
        transactions(cartao,
                tx(cartao, "-10.00", LocalDate.now(ZoneOffset.UTC)),
                tx(cartao, "-30.00", mes.minusMonths(1).atDay(3)));

        List<CardInvoicesResponse.Invoice> invoices = service.invoices(EMAIL, cartao.getId(), 3).invoices();

        assertThat(invoices.get(0).open()).isTrue();
        assertThat(invoices.get(1).open()).isFalse();
    }

    // ------------------------------------------------- V15: as três pernas

    @Test
    @DisplayName("QUADRO DOS TRÊS LANÇAMENTOS: só a compra entra no total; o pagamento não vira despesa nem receita")
    void threeLegPictureStaysCorrect() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);

        BankTransaction compra = tx(cartao, "-500.00", mes.atDay(5));
        // (3) o pagamento DENTRO do cartão: crédito, marcado como perna interna
        BankTransaction pagamentoNoCartao = tx(cartao, "500.00", mes.atDay(12));
        pagamentoNoCartao.setType("CREDIT");
        pagamentoNoCartao.setInternalTransfer(true);
        // (2) o pagamento saindo da CONTA CORRENTE não pertence a este cartão e
        // por isso nem sequer é lido: o recorte é por accountId
        transactions(cartao, compra, pagamentoNoCartao);

        CardInvoicesResponse.Invoice fatura = service.invoices(EMAIL, cartao.getId(), 1).invoices().get(0);

        // (1) a COMPRA é o valor da fatura — a despesa do mês, em regime de
        // competência, exatamente como o EC-106 estabeleceu
        assertThat(fatura.total()).isEqualByComparingTo("500.00");
        assertThat(fatura.purchasesTotal()).isEqualByComparingTo("500.00");
        // (3) o pagamento aparece como quitação, NUNCA somado ao total, nem
        // abatido dele, nem apresentado como receita
        assertThat(fatura.paymentsTotal()).isEqualByComparingTo("500.00");
        assertThat(fatura.refundsTotal()).isEqualByComparingTo("0.00");
        assertThat(fatura.transactionCount()).isEqualTo(2);
        // e a linha continua marcada como perna interna na resposta, para o app
        // não apresentá-la como receita do mês
        assertThat(fatura.transactions())
                .filteredOn(BankTransactionResponse::internalTransfer).hasSize(1);
        // toda linha da fatura carrega a origem, que é o cartão pedido na rota
        assertThat(fatura.transactions())
                .allMatch(line -> cartao.getId().equals(line.accountId()));
    }

    @Test
    @DisplayName("ESTORNO abate a fatura: compra 100 + estorno 100 no mesmo ciclo = o usuário deve ZERO")
    void refundInTheSameCycleZeroesTheInvoice() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);

        BankTransaction compra = tx(cartao, "-100.00", mes.atDay(4));
        // devolução do lojista: crédito no cartão SEM marca de perna interna —
        // é este o sinal estrutural que o separa do pagamento de fatura (V15)
        BankTransaction estorno = tx(cartao, "100.00", mes.atDay(9));
        estorno.setType("CREDIT");
        transactions(cartao, compra, estorno);

        CardInvoicesResponse.Invoice fatura = service.invoices(EMAIL, cartao.getId(), 1).invoices().get(0);

        // antes desta conta a resposta era total=100 / paymentsTotal=100, que é
        // exatamente o que uma fatura de 100 JÁ PAGA também devolvia: o app não
        // tinha como saber que aqui não há nada a pagar
        assertThat(fatura.total()).isEqualByComparingTo("0.00");
        assertThat(fatura.purchasesTotal()).isEqualByComparingTo("100.00");
        assertThat(fatura.refundsTotal()).isEqualByComparingTo("100.00");
        assertThat(fatura.paymentsTotal()).isEqualByComparingTo("0.00");
        assertThat(fatura.transactionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("estorno e pagamento no mesmo ciclo não se confundem: um abate o total, o outro não")
    void refundAndPaymentAreCountedApart() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);

        BankTransaction compra = tx(cartao, "-300.00", mes.atDay(3));
        BankTransaction estorno = tx(cartao, "50.00", mes.atDay(6));
        estorno.setType("CREDIT");
        BankTransaction pagamento = tx(cartao, "200.00", mes.atDay(8));
        pagamento.setType("CREDIT");
        pagamento.setInternalTransfer(true);
        transactions(cartao, compra, estorno, pagamento);

        CardInvoicesResponse.Invoice fatura = service.invoices(EMAIL, cartao.getId(), 1).invoices().get(0);

        // a dívida gerada pelo ciclo é 300 - 50; o pagamento quita o ciclo
        // ANTERIOR e por isso não entra nessa conta
        assertThat(fatura.total()).isEqualByComparingTo("250.00");
        assertThat(fatura.purchasesTotal()).isEqualByComparingTo("300.00");
        assertThat(fatura.refundsTotal()).isEqualByComparingTo("50.00");
        assertThat(fatura.paymentsTotal()).isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------- a fatura que vence

    @Test
    @DisplayName("months=1 no dia SEGUINTE ao fechamento ainda entrega a fatura que fechou — o ciclo em aberto é extra")
    void openCycleDoesNotConsumeTheMonthsBudget() {
        // o cenário exato: fecha dia 10, hoje é dia 11. O ciclo em aberto
        // (11/08 a 10/09) tem um dia de vida; se ele consumisse months=1, a
        // resposta seria invoices: [] no dia em que o usuário quer ver a fatura
        // que fechou ontem e ainda vai vencer
        List<CardInvoiceService.Cycle> cycles =
                service.buildCycles(10, 17, LocalDate.of(2026, 8, 11), 1);

        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0).reference()).hasToString("2026-09");
        assertThat(cycles.get(0).start()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(cycles.get(0).end()).isEqualTo(LocalDate.of(2026, 9, 10));
        // a fatura FECHADA continua na resposta, com o vencimento à frente
        assertThat(cycles.get(1).reference()).hasToString("2026-08");
        assertThat(cycles.get(1).start()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(cycles.get(1).end()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(cycles.get(1).dueDate()).isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    @DisplayName("months=1 com o cartão só movimentado no ciclo anterior devolve a fatura anterior, não uma lista vazia")
    void monthsOneStillReturnsThePreviousCycle() {
        ConnectorAccount cartao = card(null, null);
        owned(cartao);
        YearMonth mes = YearMonth.now(ZoneOffset.UTC);
        // nada no ciclo em aberto; tudo no anterior
        transactions(cartao, tx(cartao, "-70.00", mes.minusMonths(1).atDay(8)));

        List<CardInvoicesResponse.Invoice> invoices = service.invoices(EMAIL, cartao.getId(), 1).invoices();

        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).reference()).isEqualTo(mes.minusMonths(1).toString());
        assertThat(invoices.get(0).open()).isFalse();
        assertThat(invoices.get(0).total()).isEqualByComparingTo("70.00");
    }

    // ------------------------------------------------- dono e validação

    @Test
    @DisplayName("cartão de outro usuário responde 404, nunca 403: o id alheio não confirma nem que existe")
    void otherUsersAccountIsNotFound() {
        UUID alheio = UUID.randomUUID();
        when(accountService.requireOwned(alheio, user.getId()))
                .thenThrow(new ResourceNotFoundException("Conta não encontrada"));

        assertThatThrownBy(() -> service.invoices(EMAIL, alheio, 6))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(bankTransactionRepository, never())
                .findAllByUserIdAndAccountIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        any(), any(), any(), any());
    }

    @Test
    @DisplayName("conta bancária não tem fatura: 400 explicando, e não 404 — a conta existe e é do usuário")
    void bankAccountHasNoInvoice() {
        ConnectorAccount conta = ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user).name("Conta Corrente ····3456")
                .type(ConnectorAccount.AccountType.BANK).build();
        owned(conta);

        assertThatThrownBy(() -> service.invoices(EMAIL, conta.getId(), 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não é um cartão de crédito");
    }

    @Test
    @DisplayName("months fora da faixa responde 400 dizendo o limite, antes de qualquer I/O")
    void monthsOutOfRangeIsRejected() {
        assertThatThrownBy(() -> service.invoices(EMAIL, UUID.randomUUID(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("months deve estar entre 1 e 24");
        assertThatThrownBy(() -> service.invoices(EMAIL, UUID.randomUUID(), 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("months deve estar entre 1 e 24");

        // validação pura de entrada: nem o usuário é consultado
        verify(userRepository, never()).findByEmail(EMAIL);
    }

    // ------------------------------------------------- apoio

    private ConnectorAccount card(Integer closingDay, Integer dueDay) {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Ultravioleta ····1234")
                .institution("Nubank")
                .type(ConnectorAccount.AccountType.CREDIT_CARD)
                .statementClosingDay(closingDay)
                .statementDueDay(dueDay)
                .build();
    }

    private void owned(ConnectorAccount account) {
        when(accountService.requireOwned(account.getId(), user.getId())).thenReturn(account);
    }

    private void transactions(ConnectorAccount account, BankTransaction... txs) {
        when(bankTransactionRepository
                .findAllByUserIdAndAccountIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), eq(account.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(txs));
    }

    private BankTransaction tx(ConnectorAccount account, String amount, LocalDate day) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId("PLUGGY-" + UUID.randomUUID())
                .type("DEBIT")
                .amount(new BigDecimal(amount))
                .description("COMPRA")
                .accountId(account.getId())
                .date(OffsetDateTime.of(day, LocalTime.NOON, ZoneOffset.UTC))
                .build();
    }

    private static LocalDate closing(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
