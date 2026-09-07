package br.com.economize.service.family;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyMemberRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-189 — o dinheiro que circula dentro da casa.
 *
 * <p>Cenário real: o marido manda R$ 650,00 e R$ 387,11 por Pix para a esposa. A
 * casa lia os dois lados como dinheiro novo e a renda de agosto aparecia
 * R$ 1.037,11 acima da renda verdadeira do casal.
 */
@ExtendWith(MockitoExtension.class)
class FamilyTransferServiceTest {

    private static final String EMAIL = "neemias@economize.dev";
    private static final OffsetDateTime AGOSTO =
            OffsetDateTime.of(2026, 8, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private FamilyMemberRepository memberRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FamilyTransferService service;

    private User eu;
    private User esposa;
    private FamilyGroup casa;
    private FamilyMember membroEu;
    private FamilyMember membroEsposa;

    @BeforeEach
    void setUp() {
        eu = User.builder().id(UUID.randomUUID()).name("Neemias Cormino Manso")
                .email(EMAIL).password("x").build();
        esposa = User.builder().id(UUID.randomUUID()).name("Alice dos Santos Araujo")
                .email("alice@economize.dev").password("x").build();
        casa = FamilyGroup.builder().id(UUID.randomUUID()).name("Casa Salada").build();
        membroEu = FamilyMember.builder().id(UUID.randomUUID()).group(casa).user(eu).build();
        membroEsposa = FamilyMember.builder().id(UUID.randomUUID()).group(casa).user(esposa).build();
    }

    @Test
    @DisplayName("marca o Pix para a esposa, que é da mesma casa")
    void shouldMarkTransferToAnotherMember() {
        naCasa();
        BankTransaction paraEla = tx("-387.11", "Pix enviado: Cp — Alice dos Santos Araujo");
        BankTransaction mercado = tx("-92.40", "Compra no debito: SUPERMERCADO ARAUJO");
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(eu.getId()))
                .thenReturn(List.of(paraEla, mercado));

        FamilyTransferService.Outcome resultado = service.reconcile(EMAIL);

        assertThat(resultado.marked()).isEqualTo(1);
        assertThat(resultado.against()).isEqualTo(1);
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsFamilyTransfer(eq(eu.getId()), ids.capture());
        assertThat(ids.getValue()).containsExactly(paraEla.getId());
    }

    @Test
    @DisplayName("compra num estabelecimento com nome parecido não é transferência")
    void shouldIgnoreMerchantWithSimilarName() {
        naCasa();
        // "ARAUJO" sozinho não basta: falta "alice" e falta a palavra de
        // transferência. É o mesmo cuidado do EC-187 com "MERCADO SILVA"
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(eu.getId()))
                .thenReturn(List.of(tx("-92.40", "Compra no debito: ALICE ARAUJO MODAS LTDA")));

        assertThat(service.reconcile(EMAIL).marked()).isZero();
        verify(bankTransactionRepository, never()).markAsFamilyTransfer(any(), any());
    }

    @Test
    @DisplayName("nome incompleto do outro membro não casa: falta um sobrenome")
    void shouldRequireEveryNameToken() {
        naCasa();
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(eu.getId()))
                .thenReturn(List.of(tx("-100.00", "Pix enviado - Alice dos Santos Ferreira")));

        assertThat(service.reconcile(EMAIL).marked()).isZero();
    }

    @Test
    @DisplayName("linha já marcada como movimentação própria não é remarcada")
    void shouldSkipInternalTransfer() {
        naCasa();
        BankTransaction ja = tx("-387.11", "Pix enviado - Alice dos Santos Araujo");
        ja.setInternalTransfer(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(eu.getId()))
                .thenReturn(List.of(ja));

        assertThat(service.reconcile(EMAIL).marked()).isZero();
        verify(bankTransactionRepository, never()).markAsFamilyTransfer(any(), any());
    }

    @Test
    @DisplayName("rodar duas vezes não remarca o que já está marcado")
    void shouldBeIdempotent() {
        naCasa();
        BankTransaction ja = tx("-650.00", "Pix enviado - Alice dos Santos Araujo");
        ja.setFamilyTransfer(true);
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(eu.getId()))
                .thenReturn(List.of(ja));

        assertThat(service.reconcile(EMAIL).marked()).isZero();
    }

    @Test
    @DisplayName("sem casa não há dentro da casa: zero, sem tocar no extrato")
    void shouldDoNothingWithoutFamily() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(eu));
        when(memberRepository.findByUserId(eu.getId())).thenReturn(Optional.empty());

        FamilyTransferService.Outcome resultado = service.reconcile(EMAIL);

        assertThat(resultado.marked()).isZero();
        assertThat(resultado.against()).isZero();
        verify(bankTransactionRepository, never()).findAllByUserIdOrderByDateDesc(any());
    }

    @Test
    @DisplayName("membro sem nome completo é ignorado, e against explica o zero")
    void shouldReportWhenNoMemberHasFullName() {
        esposa.setName("Alice");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(eu));
        when(memberRepository.findByUserId(eu.getId())).thenReturn(Optional.of(membroEu));
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenReturn(List.of(membroEu, membroEsposa));

        FamilyTransferService.Outcome resultado = service.reconcile(EMAIL);

        assertThat(resultado.against()).isZero();
        assertThat(resultado.marked()).isZero();
        verify(bankTransactionRepository, never()).findAllByUserIdOrderByDateDesc(any());
    }

    @Test
    @DisplayName("a decisão da pessoa numa linha vence, e desmarcar também é decisão")
    void shouldLetTheUserDecideOnASingleLine() {
        BankTransaction linha = tx("-650.00", "Pix enviado - Alice dos Santos Araujo");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(eu));
        when(bankTransactionRepository.findByIdAndUserId(linha.getId(), eu.getId()))
                .thenReturn(Optional.of(linha));
        when(bankTransactionRepository.save(linha)).thenReturn(linha);

        assertThat(service.setFamilyTransfer(EMAIL, linha.getId(), true).isFamilyTransfer()).isTrue();
        assertThat(service.setFamilyTransfer(EMAIL, linha.getId(), false).isFamilyTransfer()).isFalse();
    }

    // ------------------------------------------------------------- apoio

    private void naCasa() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(eu));
        when(memberRepository.findByUserId(eu.getId())).thenReturn(Optional.of(membroEu));
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenReturn(List.of(membroEu, membroEsposa));
    }

    private BankTransaction tx(String valor, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(AGOSTO)
                .internalTransfer(false)
                .ignored(false)
                .familyTransfer(false)
                .build();
    }
}
