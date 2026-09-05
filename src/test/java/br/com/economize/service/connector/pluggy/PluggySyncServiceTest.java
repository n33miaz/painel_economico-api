package br.com.economize.service.connector.pluggy;

import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.ConnectorAccountService;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluggySyncServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private PluggyClient pluggyClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PluggyItemRepository pluggyItemRepository;

    @Mock
    private PluggyItemService pluggyItemService;

    @Mock
    private BankStatementService bankStatementService;

    @Mock
    private ConnectorAccountService accountService;

    @InjectMocks
    private PluggySyncService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        // EC-113: toda conta percorrida vira uma origem registrada. O id devolvido
        // é o que cada linha carrega até a gravação, então aqui basta ser estável
        // por conta do provedor — é isso que os testes de origem conferem.
        lenient().when(accountService.register(eq(user), any(ConnectorAccountService.AccountSnapshot.class)))
                .thenAnswer(inv -> {
                    ConnectorAccountService.AccountSnapshot snapshot = inv.getArgument(1);
                    return ConnectorAccount.builder()
                            .id(ACCOUNT_IDS.computeIfAbsent(snapshot.providerAccountId(),
                                    key -> UUID.randomUUID()))
                            .user(user)
                            .providerAccountId(snapshot.providerAccountId())
                            .name(snapshot.name())
                            .institution(snapshot.institution())
                            .type(snapshot.type())
                            .statementClosingDay(snapshot.statementClosingDay())
                            .statementDueDay(snapshot.statementDueDay())
                            .pluggyItemId(snapshot.pluggyItemId())
                            .build();
                });
    }

    /** id interno estável por conta do provedor dentro de um mesmo teste. */
    private final Map<String, UUID> ACCOUNT_IDS = new java.util.HashMap<>();

    @Test
    @DisplayName("sync percorre TODOS os itens do usuário e inclui o cartão de crédito com o sinal espelhado")
    void syncShouldWalkAllUserItemsIncludingCreditCard() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem inter = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        PluggyItem nubank = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-2").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(inter, nubank));

        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.accounts("api-key", "item-2")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT"),
                // investimento não é extrato: fica fora do pipeline
                Map.of("id", "acc-inv", "type", "INVESTMENT")));

        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of(
                        "id", "b1", "amount", "-50.00", "date", "2026-08-10",
                        "description", "PIX MERCADO CENTRAL")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // convenção do Pluggy no cartão: compra vem POSITIVA
                        Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                                "description", "IFOOD RESTAURANTE"),
                        // pagamento da fatura vem NEGATIVO no cartão
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura"),
                        // sem amount não há como classificar: linha é pulada
                        Map.of("id", "c3", "date", "2026-08-12", "description", "quebrada")));

        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 1, 0, 0, false, "PLUGGY"));

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        assertThat(sync.itemsSynced()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());
        List<ParsedTransaction> parsed = captor.getValue();
        assertThat(parsed).hasSize(3);

        ParsedTransaction bankTx = byId(parsed, "PLUGGY-b1");
        assertThat(bankTx.getAmount()).isEqualByComparingTo("-50.00");
        assertThat(bankTx.getType()).isEqualTo("DEBIT");

        // gasto de cartão é débito: o sinal do Pluggy foi espelhado
        ParsedTransaction purchase = byId(parsed, "PLUGGY-c1");
        assertThat(purchase.getAmount()).isEqualByComparingTo("-100.00");
        assertThat(purchase.getType()).isEqualTo("DEBIT");
        // a descrição real fica intocada (âncora "fatura" da recorrência)
        assertThat(purchase.getDescription()).isEqualTo("IFOOD RESTAURANTE");

        assertThat(purchase.isInternalTransfer()).isFalse();

        ParsedTransaction payment = byId(parsed, "PLUGGY-c2");
        assertThat(payment.getAmount()).isEqualByComparingTo("500.00");
        assertThat(payment.getType()).isEqualTo("CREDIT");
        assertThat(payment.getDescription()).isEqualTo("Pagamento de fatura");
        // crédito DENTRO do cartão nunca é receita do titular: quita a fatura ou
        // estorna uma compra. Marcado, sai das somas de receita/despesa
        assertThat(payment.isInternalTransfer()).isTrue();

        // débito comum da conta corrente não é afetado
        assertThat(bankTx.isInternalTransfer()).isFalse();

        // conta de investimento nunca foi consultada
        verify(pluggyClient, never()).transactions(eq("api-key"), eq("acc-inv"), any(), any());

        // carimbo de última sync nos dois itens, num save só
        assertThat(inter.getLastSyncedAt()).isNotNull();
        assertThat(nubank.getLastSyncedAt()).isNotNull();
        verify(pluggyItemRepository).saveAll(List.of(inter, nubank));
    }

    @Test
    @DisplayName("pagamento de fatura: as DUAS pernas viram transferência interna; só a COMPRA é despesa")
    void syncShouldNeutralizeBothLegsOfCardBillPayment() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem banco = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(banco));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));

        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // (1) a COMPRA — esta é a despesa do mês
                        Map.of("id", "c1", "amount", "500.00", "date", "2026-08-05",
                                "description", "IFOOD RESTAURANTE"),
                        // (3) o pagamento da fatura DENTRO do cartão
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // (2) o pagamento da fatura saindo da CONTA CORRENTE
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        // despesa comum de mesmo valor, mas sem a âncora "fatura":
                        // não pode ser confundida com perna de pagamento
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-14",
                                "description", "ALUGUEL AGOSTO")));

        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 4, 2, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());
        List<ParsedTransaction> parsed = captor.getValue();

        // (1) a compra é a única despesa de cartão que conta
        ParsedTransaction compra = byId(parsed, "PLUGGY-c1");
        assertThat(compra.getAmount()).isEqualByComparingTo("-500.00");
        assertThat(compra.getType()).isEqualTo("DEBIT");
        assertThat(compra.isInternalTransfer()).isFalse();

        // (3) perna do cartão: neutralizada — não é receita
        assertThat(byId(parsed, "PLUGGY-c2").isInternalTransfer()).isTrue();

        // (2) perna da conta corrente: neutralizada — a despesa já foi a compra
        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isTrue();

        // o aluguel de MESMO valor continua despesa: valor coincidente sozinho
        // não neutraliza nada, a âncora "fatura" também é exigida
        ParsedTransaction aluguel = byId(parsed, "PLUGGY-b2");
        assertThat(aluguel.getType()).isEqualTo("DEBIT");
        assertThat(aluguel.isInternalTransfer()).isFalse();

        // a descrição de todas segue intocada (âncora do ADR-015)
        assertThat(byId(parsed, "PLUGGY-b1").getDescription()).isEqualTo("PAGAMENTO FATURA CARTAO");
    }

    @Test
    @DisplayName("um crédito de cartão neutraliza NO MÁXIMO um débito: o excedente continua despesa")
    void cardCreditShouldNeutralizeAtMostOneDebit() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));

        // UM pagamento de fatura dentro do cartão
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "Pagamento de fatura")));
        // DOIS débitos de fatura de mesmo valor na conta corrente: um deles é de
        // outro cartão, que o usuário não conectou — e continua sendo despesa
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA OUTRO BANCO")));

        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());
        List<ParsedTransaction> parsed = captor.getValue();

        assertThat(byId(parsed, "PLUGGY-b1").isInternalTransfer()).isTrue();
        // sem multiplicidade, o mesmo crédito autorizava apagar os dois — e o mês
        // inteiro de despesas sumia em cascata a partir de uma contrapartida só
        assertThat(byId(parsed, "PLUGGY-b2").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("dois pagamentos de fatura no mês neutralizam dois débitos — a contagem é por par")
    void twoCardCreditsShouldNeutralizeTwoDebits() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura"),
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-20",
                                "description", "Pagamento de fatura parcial")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                                "description", "PAGAMENTO FATURA CARTAO"),
                        Map.of("id", "b2", "amount", "-500.00", "date", "2026-08-20",
                                "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 4, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());

        assertThat(byId(captor.getValue(), "PLUGGY-b1").isInternalTransfer()).isTrue();
        assertThat(byId(captor.getValue(), "PLUGGY-b2").isInternalTransfer()).isTrue();
    }

    @Test
    @DisplayName("LIMITAÇÃO CONHECIDA: estorno também vira contrapartida (comportamento atual)")
    void refundStillFeedsThePool() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // devolução de uma compra: no extrato do cartão ela é um
                        // crédito igualzinho ao de um pagamento de fatura
                        Map.of("id", "c1", "amount", "-100.00", "date", "2026-08-12",
                                "description", "ESTORNO IFOOD RESTAURANTE")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-100.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());

        // Este teste documenta o comportamento ATUAL, não um ideal: separar
        // estorno de pagamento exigiria adivinhar pelo texto do descritivo, que é
        // justamente o sinal frágil que o projeto recusa como critério único. O
        // preço do erro é limitado — exige um débito de valor EXATAMENTE igual ao
        // do estorno e com a âncora "fatura" na mesma janela.
        assertThat(byId(captor.getValue(), "PLUGGY-c1").isInternalTransfer()).isTrue();
        assertThat(byId(captor.getValue(), "PLUGGY-b1").isInternalTransfer()).isTrue();
    }

    @Test
    @DisplayName("sem cartão conectado o pagamento de fatura da conta corrente CONTINUA despesa")
    void syncShouldKeepBillPaymentAsExpenseWithoutConnectedCard() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem banco = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(banco));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());

        // sem a compra na base, este débito é a ÚNICA representação do gasto do
        // cartão: neutralizá-lo faria as despesas do usuário sumirem sem nada
        // no lugar
        assertThat(byId(captor.getValue(), "PLUGGY-b1").isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("lançamento PENDENTE fica de fora: ele volta efetivado com outro id e duplicaria")
    void syncShouldSkipPendingTransactions() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem cartao = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(cartao));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                                "description", "IFOOD", "status", "POSTED"),
                        Map.of("id", "c2", "amount", "80.00", "date", "2026-08-14",
                                "description", "UBER", "status", "PENDING")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());

        assertThat(captor.getValue()).extracting(ParsedTransaction::getExternalId)
                .containsExactly("PLUGGY-c1");
    }

    @Test
    @DisplayName("days fora da faixa responde 400 dizendo o limite, em vez de virar 1 dia em silêncio")
    void syncShouldRejectDaysOutOfRange() {
        assertThatThrownBy(() -> service.sync(EMAIL, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days deve estar entre 1 e 400");

        assertThatThrownBy(() -> service.sync(EMAIL, 999999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days deve estar entre 1 e 400");

        // nem o usuário é consultado: é validação de entrada pura, antes de I/O
        verify(userRepository, never()).findByEmail(anyString());
        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("sync sem nenhuma conexão registrada orienta a conectar pelo app")
    void syncShouldFailWithoutItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma conexão Pluggy");

        verify(bankStatementService, never()).importFromConnector(any(), anyString(), any(), anyList());
    }

    @Test
    @DisplayName("sync sem credenciais da aplicação falha antes de chamar o Pluggy")
    void syncShouldFailWithoutCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLUGGY_CLIENT_ID");

        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("sync semeia os itens de env do dono ANTES de listar as conexões")
    void syncShouldSeedEnvItemsBeforeListing() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem seeded = PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("env-1").build();
        // a listagem já enxerga o item recém-semeado: é a mesma sync que o migra
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(seeded));
        when(pluggyClient.accounts("api-key", "env-1")).thenReturn(List.of());
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(null, 0, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 30);

        verify(pluggyItemService).seedFromEnv(user, "api-key");
    }

    @Test
    @DisplayName("status mantém o contrato do APK (enabled/owner/configured/itemCount) contando itens de env pendentes")
    void statusShouldKeepPublishedContract() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.countByUserId(user.getId())).thenReturn(1L);
        when(pluggyItemService.pendingEnvItems(EMAIL)).thenReturn(1L);

        Map<String, Object> status = service.status(EMAIL);

        assertThat(status).containsEntry("enabled", true)
                .containsEntry("owner", true)
                .containsEntry("configured", true)
                .containsEntry("itemCount", 2L);
    }

    @Test
    @DisplayName("status de usuário sem conexões responde configured=false")
    void statusShouldReportUnconfiguredWithoutItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.countByUserId(user.getId())).thenReturn(0L);
        when(pluggyItemService.pendingEnvItems(EMAIL)).thenReturn(0L);

        Map<String, Object> status = service.status(EMAIL);

        assertThat(status).containsEntry("configured", false)
                .containsEntry("itemCount", 0L);
    }

    // ------------------------------------------------- EC-113: origem da linha

    @Test
    @DisplayName("sync registra a ORIGEM de cada conta e carimba o id dela em toda linha importada")
    void syncShouldRecordOriginForEveryAccount() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        PluggyItem nubank = PluggyItem.builder().id(UUID.randomUUID()).user(user)
                .itemId("item-1").connectorName("Nubank").build();
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(nubank));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK", "name", "Conta Corrente", "number", "12345-6"),
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Mastercard Black",
                        "marketingName", "Ultravioleta", "number", "1234",
                        "creditData", Map.of("balanceCloseDate", "2026-08-10",
                                "balanceDueDate", "2026-08-17"))));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-50.00", "date", "2026-08-10",
                        "description", "SUPERMERCADO")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 2, 1, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(2)).register(eq(user), snapshots.capture());

        ConnectorAccountService.AccountSnapshot cartao = snapshots.getAllValues().stream()
                .filter(s -> "acc-card".equals(s.providerAccountId())).findFirst().orElseThrow();
        // rótulo comercial vence o nome genérico do produto, e os últimos dígitos
        // entram porque dois cartões do mesmo banco não se distinguem por nome
        assertThat(cartao.name()).isEqualTo("Ultravioleta ····1234");
        assertThat(cartao.type()).isEqualTo(ConnectorAccount.AccountType.CREDIT_CARD);
        assertThat(cartao.institution()).isEqualTo("Nubank");
        assertThat(cartao.pluggyItemId()).isEqualTo(nubank.getId());
        // do metadado do provedor guardamos só o DIA, que é o que se repete
        assertThat(cartao.statementClosingDay()).isEqualTo(10);
        assertThat(cartao.statementDueDay()).isEqualTo(17);

        ConnectorAccountService.AccountSnapshot conta = snapshots.getAllValues().stream()
                .filter(s -> "acc-bank".equals(s.providerAccountId())).findFirst().orElseThrow();
        assertThat(conta.name()).isEqualTo("Conta Corrente ····3456");
        assertThat(conta.type()).isEqualTo(ConnectorAccount.AccountType.BANK);
        // fechamento/vencimento não existem em conta bancária
        assertThat(conta.statementClosingDay()).isNull();
        assertThat(conta.statementDueDay()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());

        // cada linha sai do sync sabendo de onde veio — depois da gravação
        // ninguém mais teria como descobrir
        assertThat(byId(captor.getValue(), "PLUGGY-c1").getAccountId()).isEqualTo(ACCOUNT_IDS.get("acc-card"));
        assertThat(byId(captor.getValue(), "PLUGGY-b1").getAccountId()).isEqualTo(ACCOUNT_IDS.get("acc-bank"));
        assertThat(ACCOUNT_IDS.get("acc-card")).isNotEqualTo(ACCOUNT_IDS.get("acc-bank"));
    }

    @Test
    @DisplayName("conta SEM id no Pluggy é pulada com WARN — não vira a origem literal \"null\"")
    void syncShouldSkipAccountWithoutProviderId() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user)
                        .itemId("item-1").connectorName("Nubank").build()));
        // duas contas malformadas: pelo unique (user_id, provider_account_id) as
        // duas colapsariam na MESMA origem "null" e fundiriam duas faturas
        Map<String, Object> semId = new java.util.HashMap<>();
        semId.put("id", null);
        semId.put("type", "CREDIT");
        semId.put("name", "Cartão sem id");
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                semId,
                Map.of("id", "null", "type", "CREDIT", "name", "Cartão com id textual null"),
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Cartão de verdade")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 1, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        // só a conta boa foi registrada, e nada foi pedido para as malformadas
        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(1)).register(eq(user), snapshots.capture());
        assertThat(snapshots.getValue().providerAccountId()).isEqualTo("acc-card");
        verify(pluggyClient, never()).transactions(eq("api-key"), eq("null"), any(), any());
        verify(pluggyClient, never()).transactions(eq("api-key"), org.mockito.ArgumentMatchers.isNull(),
                any(), any());
    }

    @Test
    @DisplayName("origem só é gravada DEPOIS de ler o extrato: falha no meio não deixa conta órfã")
    void syncShouldNotRegisterOriginWhenTransactionsFail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT"),
                Map.of("id", "acc-bank", "type", "BANK")));
        // o cartão vem primeiro (a ordenação garante isso) e é lido normalmente
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "100.00", "date", "2026-08-11",
                        "description", "IFOOD RESTAURANTE")));
        // e o Pluggy cai no meio da volta, na conta corrente
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("Pluggy fora do ar"));

        assertThatThrownBy(() -> service.sync(EMAIL, 90))
                .isInstanceOf(IllegalStateException.class);

        // a conta corrente NÃO ficou registrada com zero lançamentos: ela
        // apareceria em GET /accounts, abriria vazia e não teria como ser
        // removida. A próxima sync a cria quando conseguir ler o extrato dela
        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshots =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService, org.mockito.Mockito.times(1)).register(eq(user), snapshots.capture());
        assertThat(snapshots.getValue().providerAccountId()).isEqualTo("acc-card");
        verify(bankStatementService, never()).importFromConnector(any(), anyString(), any(), anyList());
    }

    @Test
    @DisplayName("provedor sem datas de fatura não inventa dia: fechamento e vencimento ficam nulos")
    void syncShouldLeaveStatementDaysNullWithoutProviderMetadata() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT", "name", "Cartão")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(null, 0, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        ArgumentCaptor<ConnectorAccountService.AccountSnapshot> snapshot =
                ArgumentCaptor.forClass(ConnectorAccountService.AccountSnapshot.class);
        verify(accountService).register(eq(user), snapshot.capture());
        // é essa ausência que faz a fatura declarar CALENDAR_MONTH em vez de
        // fingir uma precisão que a API não tem
        assertThat(snapshot.getValue().statementClosingDay()).isNull();
        assertThat(snapshot.getValue().statementDueDay()).isNull();
        // sem number, o rótulo é só o nome — nada de "····" pendurado
        assertThat(snapshot.getValue().name()).isEqualTo("Cartão");
    }

    @Test
    @DisplayName("o quadro dos TRÊS lançamentos: origem certa em cada um e nenhuma perna virou receita/despesa")
    void threeLegPictureKeepsOriginAndInternalMark() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK"),
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        Map.of("id", "c1", "amount", "500.00", "date", "2026-08-05",
                                "description", "IFOOD RESTAURANTE"),
                        Map.of("id", "c2", "amount", "-500.00", "date", "2026-08-12",
                                "description", "Pagamento de fatura")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "b1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "PAGAMENTO FATURA CARTAO")));
        when(bankStatementService.importFromConnector(eq(user), eq("Meu Pluggy"), eq(StatementFormat.PLUGGY), anyList()))
                .thenReturn(new BankStatementService.ImportResult(UUID.randomUUID(), 3, 0, 0, 0, false, "PLUGGY"));

        service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParsedTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(bankStatementService).importFromConnector(eq(user), eq("Meu Pluggy"),
                eq(StatementFormat.PLUGGY), captor.capture());
        List<ParsedTransaction> parsed = captor.getValue();
        UUID cartao = ACCOUNT_IDS.get("acc-card");
        UUID conta = ACCOUNT_IDS.get("acc-bank");

        // (1) a COMPRA: no cartão, despesa de verdade
        ParsedTransaction compra = byId(parsed, "PLUGGY-c1");
        assertThat(compra.getAccountId()).isEqualTo(cartao);
        assertThat(compra.isInternalTransfer()).isFalse();
        assertThat(compra.getAmount()).isEqualByComparingTo("-500.00");

        // (2) o PAGAMENTO saindo da conta corrente: outra origem, e neutralizado
        ParsedTransaction pagamentoNaConta = byId(parsed, "PLUGGY-b1");
        assertThat(pagamentoNaConta.getAccountId()).isEqualTo(conta);
        assertThat(pagamentoNaConta.isInternalTransfer()).isTrue();

        // (3) o PAGAMENTO dentro do cartão: origem do cartão, também neutralizado
        ParsedTransaction pagamentoNoCartao = byId(parsed, "PLUGGY-c2");
        assertThat(pagamentoNoCartao.getAccountId()).isEqualTo(cartao);
        assertThat(pagamentoNoCartao.isInternalTransfer()).isTrue();

        // a dimensão de conta NÃO reintroduziu a fatura como despesa nem como
        // receita: as duas pernas continuam marcadas, exatamente como na V15
        assertThat(parsed).filteredOn(ParsedTransaction::isInternalTransfer).hasSize(2);
    }

    private ParsedTransaction byId(List<ParsedTransaction> parsed, String externalId) {
        return parsed.stream()
                .filter(tx -> externalId.equals(tx.getExternalId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Transação não importada: " + externalId));
    }
}
