package br.com.economize.service.connector.pluggy;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.ConnectorAccountService;
import br.com.economize.service.StatementImportWriter;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import br.com.economize.service.statement.parser.StatementParserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prova as duas garantias que nascem de o sync entrar no MESMO pipeline do
 * upload manual, com o BankStatementService REAL (só as bordas — repositórios,
 * Pluggy, categorização — são simuladas):
 *
 * <ol>
 * <li>uma transação que o extrato OFX/CSV já trouxe, ou que uma sync anterior já
 * gravou, nunca duplica;</li>
 * <li>e a marca de movimentação entre contas do titular alcança as linhas que já
 * estavam gravadas — inclusive quando é a própria dedupe que descarta a linha
 * nova. "Pular duplicata" não pode virar "nunca corrigir".</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PluggySyncDedupeTest {

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
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private StatementUploadRepository statementUploadRepository;

    @Mock
    private StatementParserFactory parserFactory;

    @Mock
    private CategorizationEngine categorizationEngine;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private StatementImportWriter importWriter;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private CategorizationEngine.Context context;

    @Mock
    private ConnectorAccountService accountService;

    private PluggySyncService service;

    private User user;

    /** id interno estável por conta do provedor dentro de um mesmo teste. */
    private final java.util.Map<String, UUID> accountIds = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();

        @SuppressWarnings("unchecked")
        ObjectProvider<AiCategorySuggester> aiSuggester = mock(ObjectProvider.class);
        BankStatementService bankStatementService = new BankStatementService(
                bankTransactionRepository, statementUploadRepository, userRepository, parserFactory,
                categorizationEngine, categoryRepository, importWriter, accountService, eventPublisher,
                aiSuggester);
        service = new PluggySyncService(pluggyClient, userRepository, pluggyItemRepository,
                pluggyItemService, bankStatementService, accountService);

        lenient().when(categorizationEngine.contextFor(user.getId())).thenReturn(context);
        lenient().when(context.getDirtyRules()).thenReturn(new HashSet<>());
        // sem categoria resolvida: o que interessa aqui é a dedupe, não o motor
        lenient().when(categorizationEngine.categorize(eq(context), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CategorizationEngine.Result(
                        null, null, null, DescriptionNormalizer.normalize(inv.getArgument(1))));
        // EC-113: cada conta percorrida vira uma origem com id estável
        lenient().when(accountService.register(eq(user), any(ConnectorAccountService.AccountSnapshot.class)))
                .thenAnswer(inv -> {
                    ConnectorAccountService.AccountSnapshot snapshot = inv.getArgument(1);
                    return ConnectorAccount.builder()
                            .id(accountIds.computeIfAbsent(snapshot.providerAccountId(), k -> UUID.randomUUID()))
                            .user(user)
                            .providerAccountId(snapshot.providerAccountId())
                            .name(snapshot.name())
                            .type(snapshot.type())
                            .build();
                });
        lenient().when(importWriter.write(any(StatementUpload.class), anyList(), anyCollection()))
                .thenAnswer(inv -> {
                    StatementUpload upload = inv.getArgument(0);
                    upload.setId(UUID.randomUUID());
                    return upload;
                });
    }

    @Test
    @DisplayName("sync não regrava o que o upload manual já importou nem o que outra sync já trouxe")
    void syncShouldNotDuplicateManualUploadNorPreviousSync() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-bank", "type", "BANK")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-bank"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        // já veio numa sync anterior: mesmo id externo do Pluggy
                        Map.of("id", "abc", "amount", "-30.00", "date", "2026-08-09",
                                "description", "SPOTIFY"),
                        // o extrato OFX já trouxe este lançamento (id externo diferente,
                        // mas mesmo dia, valor e descrição): reconciliação, não duplicata
                        Map.of("id", "def", "amount", "-150.00", "date", "2026-08-10",
                                "description", "SUPERMERCADO BOM PRECO"),
                        // inédita: é a única que pode entrar
                        Map.of("id", "ghi", "amount", "-12.34", "date", "2026-08-11",
                                "description", "PADARIA NOVA")));

        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(
                        existing("PLUGGY-abc", "SPOTIFY", "-30.00", LocalDate.of(2026, 8, 9)),
                        existing("OFX-123", "SUPERMERCADO BOM PRECO", "-150.00", LocalDate.of(2026, 8, 10))));

        PluggySyncService.SyncResult sync = service.sync(EMAIL, 90);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BankTransaction>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(importWriter)
                .write(any(StatementUpload.class), captor.capture(), anyCollection());
        List<BankTransaction> saved = captor.getValue();

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTransactionId()).isEqualTo("PLUGGY-ghi");
        assertThat(sync.result().transactionsImported()).isEqualTo(1);
        assertThat(sync.result().reconciled()).isEqualTo(1);
    }

    // ------------------------------------------------- remarcação retroativa

    @Test
    @DisplayName("cartão conectado DEPOIS remarca o pagamento de fatura que o OFX já tinha trazido")
    void syncShouldRemarkPreviousInvoiceDebitWhenCardArrivesLater() {
        cardOnlySync();
        BankTransaction faturaAntiga = existing("OFX-9", "PAGAMENTO FATURA CARTAO", "-500.00",
                LocalDate.of(2026, 8, 12));
        emptyWindow();
        when(bankTransactionRepository.findUnmarkedByAmountInWindow(eq(user.getId()),
                any(BigDecimal.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(faturaAntiga));

        service.sync(EMAIL, 90);

        // sem isto, o débito seguiria contado como despesa para sempre e a mesma
        // compra apareceria duas vezes no relatório do usuário
        verify(bankTransactionRepository).markAsInternalTransfer(user.getId(), Set.of(faturaAntiga.getId()));

        // a busca é feita pelo valor com sinal e numa janela estreita em torno do
        // crédito: ±5 dias não alcança a fatura do mês seguinte
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bankTransactionRepository).findUnmarkedByAmountInWindow(eq(user.getId()), amount.capture(),
                start.capture(), end.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("-500.00");
        assertThat(java.time.Duration.between(start.getValue(), end.getValue()).toDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("remarcação não sequestra despesa legítima: sem a âncora 'fatura' o débito fica de fora")
    void remarkShouldRequireTheInvoiceAnchor() {
        cardOnlySync();
        emptyWindow();
        when(bankTransactionRepository.findUnmarkedByAmountInWindow(eq(user.getId()),
                any(BigDecimal.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                // mesmo valor, mesma janela, mas é o aluguel do usuário
                .thenReturn(List.of(existing("OFX-9", "ALUGUEL AGOSTO", "-500.00", LocalDate.of(2026, 8, 12))));

        service.sync(EMAIL, 90);

        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), anyCollection());
    }

    @Test
    @DisplayName("remarcação é pareamento, não transmissão: um crédito remarca UM débito")
    void remarkShouldPairOneToOne() {
        cardOnlySync();
        BankTransaction maisProxima = existing("OFX-9", "PAGAMENTO FATURA CARTAO", "-500.00",
                LocalDate.of(2026, 8, 12));
        BankTransaction outra = existing("OFX-10", "PAGAMENTO FATURA CARTAO", "-500.00",
                LocalDate.of(2026, 8, 15));
        emptyWindow();
        when(bankTransactionRepository.findUnmarkedByAmountInWindow(eq(user.getId()),
                any(BigDecimal.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(maisProxima, outra));

        service.sync(EMAIL, 90);

        // dois débitos candidatos, um crédito: só o mais próximo do crédito é par
        verify(bankTransactionRepository).markAsInternalTransfer(user.getId(), Set.of(maisProxima.getId()));
    }

    @Test
    @DisplayName("a dedupe pular a linha não pode impedir a remarcação das duas pernas")
    void dedupeMustNotBlockTheRemark() {
        cardOnlySync();
        // o crédito do cartão JÁ foi importado numa sync anterior, antes de a
        // marca existir: pelo id externo ele será pulado agora
        BankTransaction creditoJaGravado = BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId("PLUGGY-c1")
                .type("CREDIT")
                .amount(new BigDecimal("500.00"))
                .description("Pagamento de fatura")
                .normalizedDescription(DescriptionNormalizer.normalize("Pagamento de fatura"))
                .date(OffsetDateTime.of(LocalDate.of(2026, 8, 12), LocalTime.NOON, ZoneOffset.UTC))
                .build();
        BankTransaction faturaAntiga = existing("OFX-9", "PAGAMENTO FATURA CARTAO", "-500.00",
                LocalDate.of(2026, 8, 12));

        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(creditoJaGravado, faturaAntiga));
        when(bankTransactionRepository.findUnmarkedByAmountInWindow(eq(user.getId()),
                any(BigDecimal.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(faturaAntiga));

        service.sync(EMAIL, 90);

        // as DUAS pernas são corrigidas: a que a dedupe pulou (o crédito, que
        // ainda contava como receita) e a que ficou órfã no banco (o débito)
        verify(bankTransactionRepository).markAsInternalTransfer(user.getId(),
                Set.of(creditoJaGravado.getId(), faturaAntiga.getId()));
    }

    /** Sync com um cartão só, trazendo um pagamento de fatura de R$ 500. */
    private void cardOnlySync() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder().id(UUID.randomUUID()).user(user).itemId("item-1").build()));
        when(pluggyClient.accounts("api-key", "item-1")).thenReturn(List.of(
                Map.of("id", "acc-card", "type", "CREDIT")));
        when(pluggyClient.transactions(eq("api-key"), eq("acc-card"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(Map.of("id", "c1", "amount", "-500.00", "date", "2026-08-12",
                        "description", "Pagamento de fatura")));
    }

    /** Janela do lote sem nada dentro: a correção vem da busca retroativa. */
    private void emptyWindow() {
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of());
    }

    private BankTransaction existing(String transactionId, String description, String amount, LocalDate day) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(transactionId)
                .type("DEBIT")
                .amount(new BigDecimal(amount))
                .description(description)
                .normalizedDescription(DescriptionNormalizer.normalize(description))
                .date(OffsetDateTime.of(day, LocalTime.NOON, ZoneOffset.UTC))
                .build();
    }
}
