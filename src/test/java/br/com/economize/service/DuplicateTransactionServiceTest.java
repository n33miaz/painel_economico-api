package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DuplicateTransactionServiceTest {

    private static final String EMAIL = "neemias@economize.dev";
    private static final OffsetDateTime DIA_3 =
            OffsetDateTime.of(2026, 6, 3, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DuplicateTransactionService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).name("Neemias Cormino Manso")
                .email(EMAIL).password("x").build();
    }

    /** @param conta null = veio de arquivo importado; preenchido = da conexão bancária */
    private BankTransaction tx(String valor, OffsetDateTime data, UUID conta, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(data)
                .accountId(conta)
                .internalTransfer(false)
                .ignored(false)
                .build();
    }

    private void extrato(List<BankTransaction> tx) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId())).thenReturn(tx);
    }

    @SuppressWarnings("unchecked")
    private Collection<UUID> marcadas() {
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsIgnoredDuplicate(eq(user.getId()), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("A mesma fatura vinda das duas fontes: sobra a que tem conta de origem")
    void pairsConnectorWithFileAndKeepsTheOneWithAccount() {
        UUID conta = UUID.randomUUID();
        BankTransaction daConexao = tx("-2625.72", DIA_3, conta, "Pagamento fatura cartao Inter");
        BankTransaction doArquivo = tx("-2625.72", DIA_3.plusDays(1), null,
                "Pagamento efetuado - Pagamento Fatura");
        extrato(List.of(daConexao, doArquivo));

        DuplicateTransactionService.Outcome out = service.sweep(EMAIL, false);

        assertThat(out.pairs()).isEqualTo(1);
        assertThat(out.volume()).isEqualByComparingTo("2625.72");
        assertThat(out.details().get(0).kept()).isEqualTo(daConexao.getId());
        assertThat(out.details().get(0).ignoredId()).isEqualTo(doArquivo.getId());
        // o lado da conexão carrega a instituição e alimenta a tela de Cartões
        assertThat(marcadas()).containsExactly(doArquivo.getId());
    }

    @Test
    @DisplayName("dryRun relata e NÃO grava — é o padrão, porque o critério é heurístico")
    void dryRunReportsWithoutWriting() {
        UUID conta = UUID.randomUUID();
        extrato(List.of(tx("-100.00", DIA_3, conta, "Pix enviado: Cp — Alguem"),
                tx("-100.00", DIA_3, null, "Pix enviado - Alguem")));

        DuplicateTransactionService.Outcome out = service.sweep(EMAIL, true);

        assertThat(out.pairs()).isEqualTo(1);
        assertThat(out.dryRun()).isTrue();
        verify(bankTransactionRepository, never()).markAsIgnoredDuplicate(any(), anyCollection());
    }

    @Test
    @DisplayName("Duas linhas da MESMA fonte não são par, mesmo com valor e dia iguais")
    void sameSourceIsNeverAPair() {
        // Dois Pix de R$ 125 no mesmo dia para pessoas diferentes existem, e vêm
        // os dois do mesmo extrato. É o discriminante do pareamento.
        UUID conta = UUID.randomUUID();
        extrato(List.of(tx("125.00", DIA_3, conta, "Pix recebido: Cp — THIAGO"),
                tx("125.00", DIA_3, conta, "Pix recebido: Cp — RAFAEL")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
        verify(bankTransactionRepository, never()).markAsIgnoredDuplicate(any(), anyCollection());
    }

    @Test
    @DisplayName("Mais de um dia de diferença não é par")
    void beyondTheWindowIsNotAPair() {
        UUID conta = UUID.randomUUID();
        extrato(List.of(tx("-500.00", DIA_3, conta, "Pagamento de fatura"),
                tx("-500.00", DIA_3.plusDays(3), null, "Pagamento de fatura")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("Valor diferente não é par, nem por um centavo")
    void differentAmountIsNotAPair() {
        UUID conta = UUID.randomUUID();
        extrato(List.of(tx("-500.00", DIA_3, conta, "Pagamento de fatura"),
                tx("-500.01", DIA_3, null, "Pagamento de fatura")));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("Cada lado é pareado uma vez só: três linhas iguais dão UM par")
    void eachSideIsPairedOnlyOnce() {
        // Sem essa trava, duas linhas da conexão casariam com a mesma do arquivo
        // (ou vice-versa) e a varredura marcaria mais do que existe de duplicata
        UUID conta = UUID.randomUUID();
        BankTransaction a = tx("-416.78", DIA_3, conta, "Pagamento de fatura");
        BankTransaction b = tx("-416.78", DIA_3.plusDays(1), null, "Pagamento de fatura");
        BankTransaction c = tx("-416.78", DIA_3.plusDays(1), null, "Pagamento de fatura");
        extrato(List.of(a, b, c));

        DuplicateTransactionService.Outcome out = service.sweep(EMAIL, false);

        assertThat(out.pairs()).isEqualTo(1);
        assertThat(marcadas()).hasSize(1);
    }

    @Test
    @DisplayName("Linha já ignorada fica fora da varredura — rodar duas vezes é seguro")
    void alreadyIgnoredIsSkipped() {
        UUID conta = UUID.randomUUID();
        BankTransaction daConexao = tx("-90.00", DIA_3, conta, "Resgate: CDB");
        BankTransaction jaIgnorada = tx("-90.00", DIA_3, null, "Resgate - Cdb");
        jaIgnorada.setIgnored(true);
        extrato(List.of(daConexao, jaIgnorada));

        assertThat(service.sweep(EMAIL, false).pairs()).isZero();
    }

    @Test
    @DisplayName("A marca manual grava o motivo USER, e limpar apaga o motivo")
    void manualMarkCarriesTheReason() {
        BankTransaction linha = tx("-10.00", DIA_3, null, "Alguma coisa");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findByIdAndUserId(linha.getId(), user.getId()))
                .thenReturn(Optional.of(linha));
        when(bankTransactionRepository.save(linha)).thenReturn(linha);

        service.setIgnored(EMAIL, linha.getId(), true);
        assertThat(linha.isIgnored()).isTrue();
        // USER e não DUPLICATE: só o motivo da varredura pode ser revisto por ela
        assertThat(linha.getIgnoredReason()).isEqualTo(BankTransaction.IgnoredReason.USER);

        service.setIgnored(EMAIL, linha.getId(), false);
        assertThat(linha.isIgnored()).isFalse();
        assertThat(linha.getIgnoredReason()).isNull();
    }
}
