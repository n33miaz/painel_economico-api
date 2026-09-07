package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalTransferServiceTest {

    private static final String EMAIL = "neemias@economize.dev";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InternalTransferService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .name("Neemias Cormino Manso")
                .email(EMAIL)
                .password("x")
                .build();
    }

    private BankTransaction tx(String description, String type) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type(type)
                .amount(new BigDecimal("100.00"))
                .description(description)
                .date(OffsetDateTime.now())
                .internalTransfer(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Collection<UUID> marked() {
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(bankTransactionRepository).markAsInternalTransfer(eq(user.getId()), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Pix cuja contraparte é o próprio titular vira movimentação própria")
    void marksTransfersWhereCounterpartIsTheHolder() {
        BankTransaction proprio = tx("Pix recebido - Neemias Cormino Manso", "CREDIT");
        BankTransaction tedPropria = tx("Transferência Recebida|NEEMIAS CORMINO MANSO", "CREDIT");
        BankTransaction deOutro = tx("Pix recebido - Claudia Cristina P Santana", "CREDIT");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(proprio, tedPropria, deOutro));

        InternalTransferService.Outcome outcome = service.reconcileByOwnName(EMAIL);

        assertThat(outcome.scanned()).isEqualTo(3);
        assertThat(outcome.marked()).isEqualTo(2);
        assertThat(outcome.hasFullName()).isTrue();
        assertThat(marked()).containsExactlyInAnyOrder(proprio.getId(), tedPropria.getId());
    }

    @Test
    @DisplayName("Compra num estabelecimento com o mesmo nome NÃO é transferência")
    void doesNotTouchPurchasesThatMerelyShareTheName() {
        // O sinal exige palavra de transferência na descrição. Sem isso, uma loja
        // chamada como o titular (e "MERCADO SILVA" existe) viraria dinheiro dele
        BankTransaction compra = tx(
                "Compra no debito: \"No estabelecimento MANSO CORMINO NEEMIAS BARUERI BRA\"", "DEBIT");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(compra));

        InternalTransferService.Outcome outcome = service.reconcileByOwnName(EMAIL);

        assertThat(outcome.marked()).isZero();
        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), anyCollection());
    }

    @Test
    @DisplayName("Nome parecido não basta: falta um token, não é o titular")
    void requiresEveryTokenOfTheName() {
        BankTransaction outroSobrenome = tx("Pix enviado - Neemias Cormino Souza", "DEBIT");
        BankTransaction soPrimeiroNome = tx("Pix enviado - Neemias", "DEBIT");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(outroSobrenome, soPrimeiroNome));

        assertThat(service.reconcileByOwnName(EMAIL).marked()).isZero();
    }

    @Test
    @DisplayName("Sem nome completo no cadastro a varredura não adivinha nada")
    void refusesToGuessWithoutAFullName() {
        user.setName("Neemias");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        InternalTransferService.Outcome outcome = service.reconcileByOwnName(EMAIL);

        assertThat(outcome.hasFullName()).isFalse();
        assertThat(outcome.marked()).isZero();
        // nem leu o extrato: um "Neemias" solto casaria com qualquer Neemias
        verify(bankTransactionRepository, never()).findAllByUserIdOrderByDateDesc(any());
    }

    @Test
    @DisplayName("Linha já marcada não é reexaminada — rodar duas vezes é seguro")
    void leavesAlreadyMarkedLinesAlone() {
        BankTransaction jaMarcada = tx("Pix recebido - Neemias Cormino Manso", "CREDIT");
        jaMarcada.setInternalTransfer(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(jaMarcada));

        assertThat(service.reconcileByOwnName(EMAIL).marked()).isZero();
        verify(bankTransactionRepository, never()).markAsInternalTransfer(any(), anyCollection());
    }

    @Test
    @DisplayName("Partícula do nome não é exigida: 'Alice dos Santos' casa 'Alice Santos'")
    void ignoresNameParticles() {
        user.setName("Alice dos Santos Araujo");
        BankTransaction dela = tx("Pix recebido ALICE SANTOS ARAUJO", "CREDIT");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(dela));

        assertThat(service.reconcileByOwnName(EMAIL).marked()).isEqualTo(1);
    }

    @Test
    @DisplayName("A marca manual do usuário grava, nos dois sentidos")
    void manualDecisionPersists() {
        BankTransaction linha = tx("Pix recebido - Alguem", "CREDIT");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findByIdAndUserId(linha.getId(), user.getId()))
                .thenReturn(Optional.of(linha));
        when(bankTransactionRepository.save(linha)).thenReturn(linha);

        assertThat(service.setInternal(EMAIL, linha.getId(), true).isInternalTransfer()).isTrue();
        assertThat(linha.isInternalTransfer()).isTrue();

        assertThat(service.setInternal(EMAIL, linha.getId(), false).isInternalTransfer()).isFalse();
    }

    @Test
    @DisplayName("Linha de outro usuário responde 404, não 403")
    void otherUsersLineIsNotFound() {
        UUID alheia = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findByIdAndUserId(alheia, user.getId()))
                .thenReturn(Optional.empty());

        // o dono é filtro da consulta, não checagem posterior — padrão da casa
        assertThatThrownBy(() -> service.setInternal(EMAIL, alheia, true))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
