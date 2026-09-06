package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A ORIGEM do lançamento (EC-113) no pipeline de importação, com o
 * BankStatementService REAL: o que acontece quando ela existe, e — o caso que
 * mais importa para não mentir ao usuário — o que acontece quando ela
 * genuinamente não existe.
 */
@ExtendWith(MockitoExtension.class)
class BankStatementAccountOriginTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private StatementUploadRepository statementUploadRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StatementParserFactory parserFactory;

    // Só os dois testes de upload com origem passam pelo parser; os demais
    // entram pelo caminho do conector, que já recebe as linhas prontas
    @Mock
    private br.com.economize.service.statement.parser.StatementParserStrategy parser;

    @Mock
    private CategorizationEngine categorizationEngine;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private StatementImportWriter importWriter;

    // So entra em cena quando o upload declara a origem (?accountId=)
    @Mock
    private ConnectorAccountService accountService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private CategorizationEngine.Context context;

    private BankStatementService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        @SuppressWarnings("unchecked")
        ObjectProvider<AiCategorySuggester> aiSuggester = mock(ObjectProvider.class);
        service = new BankStatementService(bankTransactionRepository, statementUploadRepository,
                userRepository, parserFactory, categorizationEngine, categoryRepository,
                importWriter, accountService, eventPublisher, aiSuggester);

        lenient().when(categorizationEngine.contextFor(user.getId())).thenReturn(context);
        lenient().when(context.getDirtyRules()).thenReturn(new HashSet<>());
        lenient().when(categorizationEngine.categorize(eq(context), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CategorizationEngine.Result(
                        null, null, null, DescriptionNormalizer.normalize(inv.getArgument(1))));
        lenient().when(importWriter.write(any(StatementUpload.class), anyList(), anyCollection()))
                .thenAnswer(inv -> {
                    StatementUpload upload = inv.getArgument(0);
                    upload.setId(UUID.randomUUID());
                    return upload;
                });
    }

    @Test
    @DisplayName("upload que declara a conta carimba a origem em TODAS as linhas do arquivo")
    void uploadWithAccountStampsEveryLine() {
        emptyWindow();
        UUID conta = UUID.randomUUID();
        ConnectorAccount origem = ConnectorAccount.builder().id(conta).user(user).build();
        when(accountService.requireOwned(conta, user.getId())).thenReturn(origem);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(parserFactory.resolve(StatementFormat.OFX)).thenReturn(parser);
        when(parser.parse(any())).thenReturn(List.of(
                ParsedTransaction.builder()
                        .externalId("OFX-1").type("DEBIT").amount(new BigDecimal("-42.00"))
                        .description("PADARIA REAL").date(day(2026, 8, 10)).build(),
                ParsedTransaction.builder()
                        .externalId("OFX-2").type("CREDIT").amount(new BigDecimal("2500.00"))
                        .description("SALARIO").date(day(2026, 8, 5)).build()));

        service.processFile(user.getEmail(), filePart("extrato.ofx"), conta).block();

        // Sem isto, duas contas correntes e um cartão importados por arquivo
        // viravam uma lista indistinta: o Extrato não tinha como separá-las
        assertThat(saved()).extracting(BankTransaction::getAccountId).containsOnly(conta);
    }

    @Test
    @DisplayName("conta de OUTRA pessoa recusa antes de importar qualquer linha")
    void uploadWithForeignAccountImportsNothing() {
        UUID alheia = UUID.randomUUID();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(accountService.requireOwned(alheia, user.getId()))
                .thenThrow(new ResourceNotFoundException("Conta não encontrada"));

        assertThatThrownBy(() -> service.processFile(user.getEmail(), filePart("extrato.ofx"), alheia).block())
                .isInstanceOf(ResourceNotFoundException.class);

        // a recusa vem ANTES da leitura: meio extrato gravado sem dono seria
        // pior do que não importar nada
        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    @Test
    @DisplayName("importação antiga ganha origem sem precisar reimportar o arquivo")
    void assignsTheOriginToAnAlreadyImportedFile() {
        UUID upload = UUID.randomUUID();
        UUID conta = UUID.randomUUID();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(statementUploadRepository.findByIdAndUserId(upload, user.getId()))
                .thenReturn(Optional.of(StatementUpload.builder().id(upload).build()));
        when(accountService.requireOwned(conta, user.getId()))
                .thenReturn(ConnectorAccount.builder().id(conta).user(user).build());
        when(bankTransactionRepository.assignAccountToUpload(user.getId(), conta, upload)).thenReturn(37);

        // Reimportar não resolveria: o upload é idempotente por hash e a
        // segunda tentativa não grava nada
        assertThat(service.assignUploadAccount(user.getEmail(), upload, conta)).isEqualTo(37);
    }

    @Test
    @DisplayName("importação de outra pessoa responde 404 e não carimba nada")
    void refusesToAssignAForeignUpload() {
        UUID upload = UUID.randomUUID();
        UUID conta = UUID.randomUUID();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(statementUploadRepository.findByIdAndUserId(upload, user.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignUploadAccount(user.getEmail(), upload, conta))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(bankTransactionRepository, never()).assignAccountToUpload(any(), any(), any());
    }

    @Test
    @DisplayName("upload manual de arquivo grava origem NULA — não existe conta de provedor para inventar")
    void manualUploadShouldLeaveOriginNull() {
        emptyWindow();
        // é exatamente isto que os parsers de OFX/CSV/TXT/XLSX produzem: nenhum
        // deles conhece conta de provedor, e o campo fica no default
        List<ParsedTransaction> doArquivo = List.of(
                ParsedTransaction.builder()
                        .externalId("OFX-1").type("DEBIT").amount(new BigDecimal("-42.00"))
                        .description("PADARIA REAL").date(day(2026, 8, 10)).build(),
                ParsedTransaction.builder()
                        .externalId("OFX-2").type("CREDIT").amount(new BigDecimal("2500.00"))
                        .description("SALARIO").date(day(2026, 8, 5)).build());

        service.importFromConnector(user, "extrato.ofx", StatementFormat.OFX, doArquivo);

        // nulo é o valor CORRETO e permanente aqui: a API o apresenta como
        // "origem não informada", jamais como uma conta adivinhada
        assertThat(saved()).extracting(BankTransaction::getAccountId).containsOnlyNulls();
        // e nada de carimbar origem em linha nenhuma quando não há origem
        verify(bankTransactionRepository, never()).assignAccount(any(), any(), anyCollection());
    }

    @Test
    @DisplayName("linha do conector grava a origem que veio com ela")
    void connectorLineShouldPersistOrigin() {
        emptyWindow();
        UUID cartao = UUID.randomUUID();

        service.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, List.of(
                ParsedTransaction.builder()
                        .externalId("PLUGGY-c1").type("DEBIT").amount(new BigDecimal("-100.00"))
                        .description("IFOOD").date(day(2026, 8, 11)).accountId(cartao).build()));

        assertThat(saved()).singleElement()
                .extracting(BankTransaction::getAccountId).isEqualTo(cartao);
    }

    @Test
    @DisplayName("a dedupe pular a linha não pode impedir o carimbo da origem no que já estava gravado")
    void dedupeMustNotBlockTheOriginBackfill() {
        UUID cartao = UUID.randomUUID();
        // gravada por uma sync ANTERIOR à dimensão de conta existir: é o extrato
        // inteiro dos usuários atuais. Pelo id externo ela será pulada agora.
        BankTransaction semOrigem = existing("PLUGGY-c1", "IFOOD", "-100.00", LocalDate.of(2026, 8, 11));
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(semOrigem));

        service.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, List.of(
                ParsedTransaction.builder()
                        .externalId("PLUGGY-c1").type("DEBIT").amount(new BigDecimal("-100.00"))
                        .description("IFOOD").date(day(2026, 8, 11)).accountId(cartao).build()));

        // sem isto, "pular duplicata" significaria "o extrato já sincronizado
        // nunca terá origem" e a fatura do usuário abriria vazia
        verify(bankTransactionRepository).assignAccount(user.getId(), cartao, List.of(semOrigem.getId()));
        assertThat(saved()).isEmpty();
    }

    @Test
    @DisplayName("o backfill da primeira sync vai EM LOTES: nada de um IN com milhares de placeholders")
    void originBackfillIsChunked() {
        UUID cartao = UUID.randomUUID();
        // a primeira sync pós-deploy é o pior caso por construção: TODO o extrato
        // já importado é duplicata pelo id externo e cai no backfill de uma vez
        List<BankTransaction> jaGravadas = new java.util.ArrayList<>();
        List<ParsedTransaction> daSync = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            jaGravadas.add(existing("PLUGGY-" + i, "IFOOD", "-10.00", LocalDate.of(2026, 8, 11)));
            daSync.add(ParsedTransaction.builder()
                    .externalId("PLUGGY-" + i).type("DEBIT").amount(new BigDecimal("-10.00"))
                    .description("IFOOD").date(day(2026, 8, 11)).accountId(cartao).build());
        }
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(jaGravadas);

        service.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, daSync);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(bankTransactionRepository, times(3)).assignAccount(eq(user.getId()), eq(cartao), captor.capture());
        assertThat(captor.getAllValues()).extracting(java.util.Collection::size)
                .containsExactly(500, 500, 200);
        // e nenhum id se perdeu no fatiamento
        assertThat(captor.getAllValues().stream().flatMap(java.util.Collection::stream).distinct())
                .hasSize(1200);
    }

    @Test
    @DisplayName("reconciliação por dia+valor NÃO carimba origem: só o id externo é prova de identidade")
    void reconciliationByDayAndAmountMustNotAssignOrigin() {
        // o OFX da conta corrente já trouxe este lançamento com OUTRO id. A
        // reconciliação vai casá-lo por dia+valor+descrição — mas isso é
        // pareamento plausível, não prova: carimbar a conta do cartão aqui
        // poderia atribuir a compra à origem errada
        BankTransaction doOfx = existing("OFX-77", "IFOOD", "-100.00", LocalDate.of(2026, 8, 11));
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(doOfx));

        service.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, List.of(
                ParsedTransaction.builder()
                        .externalId("PLUGGY-c1").type("DEBIT").amount(new BigDecimal("-100.00"))
                        .description("IFOOD").date(day(2026, 8, 11)).accountId(UUID.randomUUID()).build()));

        verify(bankTransactionRepository, never()).assignAccount(any(), any(), anyCollection());
    }

    @Test
    @DisplayName("origem já decidida nunca é sobrescrita — a condição vive na própria consulta")
    void existingOriginIsNeverOverwritten() {
        BankTransaction jaComOrigem = existing("PLUGGY-c1", "IFOOD", "-100.00", LocalDate.of(2026, 8, 11));
        jaComOrigem.setAccountId(UUID.randomUUID());
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(jaComOrigem));

        service.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, List.of(
                ParsedTransaction.builder()
                        .externalId("PLUGGY-c1").type("DEBIT").amount(new BigDecimal("-100.00"))
                        .description("IFOOD").date(day(2026, 8, 11)).accountId(UUID.randomUUID()).build()));

        verify(bankTransactionRepository, never()).assignAccount(any(), any(), anyCollection());
    }

    @SuppressWarnings("unchecked")
    private List<BankTransaction> saved() {
        ArgumentCaptor<List<BankTransaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(importWriter).write(any(StatementUpload.class), captor.capture(), anyCollection());
        return captor.getValue();
    }

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

    private static OffsetDateTime day(int year, int month, int day) {
        return OffsetDateTime.of(LocalDate.of(year, month, day), LocalTime.NOON, ZoneOffset.UTC);
    }

    /**
     * Um {@code FilePart} de mentira. O conteúdo é irrelevante — quem decide o
     * que sai da leitura é o parser, que aqui está dublado —, mas os bytes
     * precisam existir para o hash de idempotência ser calculado.
     */
    private org.springframework.http.codec.multipart.FilePart filePart(String nome) {
        var part = mock(org.springframework.http.codec.multipart.FilePart.class);
        when(part.filename()).thenReturn(nome);
        when(part.content()).thenReturn(reactor.core.publisher.Flux.just(
                new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                        .wrap("conteudo".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return part;
    }
}
