package br.com.economize.service;

import br.com.economize.model.BankTransaction;
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
import br.com.economize.service.statement.parser.StatementParserStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Os caminhos de ENTRADA da importação (EC-155): arquivo repetido, arquivo
 * vazio e a porta dos conectores.
 *
 * <p>Os testes que já existiam cobrem a reconciliação — o miolo. Faltavam as
 * bordas, que são as que o usuário encontra primeiro: reimportar o mesmo
 * extrato (o gesto mais comum de quem não lembra se já importou) e mandar um
 * arquivo que não tem transação nenhuma.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankStatementService — as bordas da importação")
class BankStatementImportPathsTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private StatementUploadRepository statementUploadRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StatementParserFactory parserFactory;
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
    @Mock
    private StatementParserStrategy parser;

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
        lenient().when(bankTransactionRepository
                        .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                                any(), any(), any()))
                .thenReturn(List.of());
    }

    /**
     * Um {@link FilePart} de verdade, com o conteúdo em memória: a rota real
     * recebe o arquivo por aqui, e é este caminho que decide formato e hash.
     */
    private FilePart arquivo(String nome, byte[] conteudo) {
        FilePart part = mock(FilePart.class);
        DataBuffer buffer = new DefaultDataBufferFactory().wrap(conteudo);
        lenient().when(part.filename()).thenReturn(nome);
        lenient().when(part.content()).thenReturn(Flux.just(buffer));
        return part;
    }

    private BankStatementService.ImportResult importar(String nome, byte[] conteudo) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return service.processFile(EMAIL, arquivo(nome, conteudo)).block();
    }

    private ParsedTransaction tx(String id, String valor, String descricao) {
        return ParsedTransaction.builder()
                .externalId(id)
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    // ------------------------------------------------ o mesmo arquivo de novo

    @Test
    @DisplayName("Reimportar o mesmo arquivo devolve o resultado ORIGINAL, sem gravar de novo")
    void arquivoRepetidoNaoDuplica() {
        StatementUpload anterior = new StatementUpload();
        anterior.setId(UUID.randomUUID());
        anterior.setTransactionsImported(7);
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.of(anterior));
        when(bankTransactionRepository.findAllByUserIdAndUploadIdOrderByDateDesc(
                user.getId(), anterior.getId()))
                .thenReturn(List.of(
                        comStatus(BankTransaction.ReviewStatus.SUGGESTED),
                        comStatus(BankTransaction.ReviewStatus.SUGGESTED),
                        comStatus(BankTransaction.ReviewStatus.UNCATEGORIZED),
                        comStatus(BankTransaction.ReviewStatus.CONFIRMED)));

        BankStatementService.ImportResult resultado = importar("extrato.csv", new byte[] { 1, 2, 3 });

        assertThat(resultado.duplicated()).isTrue();
        assertThat(resultado.uploadId()).isEqualTo(anterior.getId());
        // O número que volta é o da importação ORIGINAL, e a contagem de
        // pendências é a de AGORA: reimportar não pode dizer "0 importadas" a
        // quem só quer saber se o extrato já entrou
        assertThat(resultado.transactionsImported()).isEqualTo(7);
        assertThat(resultado.suggested()).isEqualTo(2);
        assertThat(resultado.uncategorized()).isEqualTo(1);

        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    private BankTransaction comStatus(BankTransaction.ReviewStatus status) {
        return BankTransaction.builder().id(UUID.randomUUID()).reviewStatus(status).build();
    }

    // ------------------------------------------------------- arquivo inútil

    @Test
    @DisplayName("Arquivo sem transação nenhuma é erro do cliente, com mensagem que explica")
    void arquivoSemTransacaoERecusado() {
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.empty());
        when(parserFactory.resolve(any(StatementFormat.class))).thenReturn(parser);
        when(parser.parse(any())).thenReturn(List.of());

        // `block()` republica RuntimeException como ela é, sem embrulhar
        assertThatThrownBy(() -> importar("vazio.csv", new byte[] { 1 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nenhuma transação");

        // Gravar um upload de zero linhas encheria o histórico de importações
        // com registros que não representam nada
        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    @Test
    @DisplayName("O formato sai do NOME do arquivo, e é ele que escolhe o parser")
    void formatoVemDoNomeDoArquivo() {
        when(statementUploadRepository.findByUserIdAndFileHash(eq(user.getId()), anyString()))
                .thenReturn(Optional.empty());
        when(parserFactory.resolve(any(StatementFormat.class))).thenReturn(parser);
        when(parser.parse(any())).thenReturn(List.of(tx("ext-1", "-45.90", "MERCADO")));

        BankStatementService.ImportResult resultado = importar("extrato-maio.ofx", new byte[] { 1 });

        verify(parserFactory).resolve(StatementFormat.OFX);
        assertThat(resultado.format()).isEqualTo("OFX");
        assertThat(resultado.duplicated()).isFalse();
    }

    // ------------------------------------------------------- via conector

    @Test
    @DisplayName("Sincronização sem novidade não vira upload no histórico")
    void conectorSemNovidadeNaoGravaUpload() {
        BankStatementService.ImportResult resultado =
                service.importFromConnector(user, "Nubank", StatementFormat.OFX, List.of());

        assertThat(resultado.transactionsImported()).isZero();
        assertThat(resultado.uploadId()).isNull();
        assertThat(resultado.duplicated()).isFalse();
        verify(importWriter, never()).write(any(), anyList(), anyCollection());
    }

    @Test
    @DisplayName("Cada sincronização do conector é um registro próprio, mesmo com o mesmo conteúdo")
    void conectorGravaCadaSincronizacao() {
        List<ParsedTransaction> lote = List.of(tx("ext-1", "-45.90", "MERCADO"));

        service.importFromConnector(user, "Nubank", StatementFormat.OFX, lote);
        service.importFromConnector(user, "Nubank", StatementFormat.OFX, lote);

        // O hash sintético é único por sincronização: sem isso a segunda cairia
        // no caminho de "arquivo repetido" e a sync viraria silêncio
        verify(importWriter, org.mockito.Mockito.times(2))
                .write(any(StatementUpload.class), anyList(), anyCollection());
    }

    @Test
    @DisplayName("A dedupe por id externo não regrava o que já está no banco")
    void idExternoJaConhecidoNaoRegrava() {
        BankTransaction jaExiste = BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId("ext-1")
                .type("DEBIT")
                .amount(new BigDecimal("-45.90"))
                .description("MERCADO")
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .build();
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        any(), any(), any()))
                .thenReturn(List.of(jaExiste));

        BankStatementService.ImportResult resultado = service.importFromConnector(
                user, "Nubank", StatementFormat.OFX,
                List.of(tx("ext-1", "-45.90", "MERCADO"), tx("ext-2", "-10.00", "PADARIA")));

        // Só a linha nova entra; a conhecida é pulada pelo id externo
        assertThat(resultado.transactionsImported()).isEqualTo(1);
    }
}
