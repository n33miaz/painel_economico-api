package br.com.economize.service;

import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.InvoiceReserve;
import br.com.economize.model.User;
import br.com.economize.repository.InvoiceReserveRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-181 — o dinheiro separado para a fatura.
 *
 * <p>O caso que originou a funcionalidade: o dono deixou na conta Mercado Pago
 * exatamente os R$ 641,14 da compra parcelada do Mercado Livre, e o sistema
 * lia aquilo como saldo livre.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceReserveServiceTest {

    private static final String EMAIL = "dono@economize.app";

    @Mock
    private InvoiceReserveRepository repository;

    @Mock
    private ConnectorAccountService accountService;

    @Mock
    private UserRepository userRepository;

    private InvoiceReserveService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        service = new InvoiceReserveService(repository, accountService, userRepository);
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(repository.save(any(InvoiceReserve.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("grava a reserva com a conta que guarda o dinheiro")
    void shouldSaveReserveWithHoldingAccount() {
        ConnectorAccount cartao = card();
        ConnectorAccount cofre = checking();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);
        when(accountService.requireOwned(cofre.getId(), user.getId())).thenReturn(cofre);
        when(repository.findByUserIdAndCardAccountIdAndReference(user.getId(), cartao.getId(), "2026-09"))
                .thenReturn(Optional.empty());

        CardInvoicesResponse.Reserve resposta = service.save(EMAIL, cartao.getId(), "2026-09",
                new BigDecimal("641.14"), cofre.getId(), "deixei separado");

        assertThat(resposta.amount()).isEqualByComparingTo("641.14");
        assertThat(resposta.heldInAccountId()).isEqualTo(cofre.getId());
        assertThat(resposta.heldInAccountName()).isEqualTo("Mercado Pago ····7340");
        assertThat(resposta.note()).isEqualTo("deixei separado");
    }

    @Test
    @DisplayName("chamar de novo sobrescreve o valor em vez de criar uma segunda reserva")
    void shouldUpdateExistingReserve() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);
        InvoiceReserve existente = InvoiceReserve.builder()
                .id(UUID.randomUUID()).user(user).cardAccount(cartao)
                .reference("2026-09").amount(new BigDecimal("320.57")).build();
        when(repository.findByUserIdAndCardAccountIdAndReference(user.getId(), cartao.getId(), "2026-09"))
                .thenReturn(Optional.of(existente));

        CardInvoicesResponse.Reserve resposta =
                service.save(EMAIL, cartao.getId(), "2026-09", new BigDecimal("641.14"), null, null);

        ArgumentCaptor<InvoiceReserve> captor = ArgumentCaptor.forClass(InvoiceReserve.class);
        verify(repository).save(captor.capture());
        // a MESMA linha, com o valor corrigido: a fatura em aberto cresce até
        // fechar, e cada correção não pode virar uma reserva nova
        assertThat(captor.getValue().getId()).isEqualTo(existente.getId());
        assertThat(resposta.id()).isEqualTo(existente.getId());
        assertThat(resposta.amount()).isEqualByComparingTo("641.14");
        assertThat(resposta.heldInAccountName()).isNull();
    }

    @Test
    @DisplayName("aceita reserva sem dizer onde o dinheiro está")
    void shouldAcceptReserveWithoutHoldingAccount() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);
        when(repository.findByUserIdAndCardAccountIdAndReference(any(), any(), any()))
                .thenReturn(Optional.empty());

        CardInvoicesResponse.Reserve resposta =
                service.save(EMAIL, cartao.getId(), "2026-09", new BigDecimal("100.00"), null, "   ");

        assertThat(resposta.heldInAccountId()).isNull();
        // anotação só de espaço não vira anotação
        assertThat(resposta.note()).isNull();
    }

    @Test
    @DisplayName("conta corrente não tem fatura, então não aceita reserva")
    void shouldRejectReserveOnNonCard() {
        ConnectorAccount conta = checking();
        when(accountService.requireOwned(conta.getId(), user.getId())).thenReturn(conta);

        assertThatThrownBy(() -> service.save(EMAIL, conta.getId(), "2026-09",
                new BigDecimal("10.00"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não é um cartão de crédito");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("valor zero ou negativo é recusado")
    void shouldRejectNonPositiveAmount() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);

        assertThatThrownBy(() -> service.save(EMAIL, cartao.getId(), "2026-09", BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maior que zero");
        assertThatThrownBy(() -> service.save(EMAIL, cartao.getId(), "2026-09", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("ciclo fora do formato AAAA-MM é recusado antes de gravar órfão")
    void shouldRejectMalformedReference() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);

        // "2026-9" e "set/26" gravariam sem erro e nunca casariam com ciclo
        // nenhum: o dono veria a fatura descoberta sem entender por quê
        assertThatThrownBy(() -> service.save(EMAIL, cartao.getId(), "set/26",
                new BigDecimal("10.00"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AAAA-MM");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a referência é normalizada, então 2026-09 e 2026-9 apontam para o mesmo ciclo")
    void shouldNormalizeReference() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);
        when(repository.findByUserIdAndCardAccountIdAndReference(user.getId(), cartao.getId(), "2026-09"))
                .thenReturn(Optional.empty());

        service.save(EMAIL, cartao.getId(), " 2026-09 ", new BigDecimal("10.00"), null, null);

        ArgumentCaptor<InvoiceReserve> captor = ArgumentCaptor.forClass(InvoiceReserve.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getReference()).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("desfazer reserva que não existe não é erro")
    void shouldDeleteIdempotently() {
        ConnectorAccount cartao = card();
        when(accountService.requireOwned(cartao.getId(), user.getId())).thenReturn(cartao);
        when(repository.findByUserIdAndCardAccountIdAndReference(user.getId(), cartao.getId(), "2026-09"))
                .thenReturn(Optional.empty());

        service.delete(EMAIL, cartao.getId(), "2026-09");

        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("as reservas do cartão voltam indexadas pelo ciclo")
    void shouldIndexReservesByReference() {
        ConnectorAccount cartao = card();
        when(repository.findAllByUserIdAndCardAccountId(user.getId(), cartao.getId())).thenReturn(List.of(
                reserve(cartao, "2026-08", "320.57"),
                reserve(cartao, "2026-09", "641.14")));

        Map<String, CardInvoicesResponse.Reserve> mapa =
                service.byReference(user.getId(), cartao.getId());

        assertThat(mapa).hasSize(2);
        assertThat(mapa.get("2026-09").amount()).isEqualByComparingTo("641.14");
    }

    @Test
    @DisplayName("o total separado soma todos os cartões e ciclos")
    void shouldSumEveryReserve() {
        ConnectorAccount cartao = card();
        when(repository.findAllByUserId(user.getId())).thenReturn(List.of(
                reserve(cartao, "2026-08", "320.57"),
                reserve(cartao, "2026-09", "641.14")));

        assertThat(service.totalReserved(user.getId())).isEqualByComparingTo("961.71");
    }

    @Test
    @DisplayName("sem nenhuma reserva o total é zero, nunca nulo")
    void shouldSumToZeroWithoutReserves() {
        when(repository.findAllByUserId(user.getId())).thenReturn(List.of());

        assertThat(service.totalReserved(user.getId())).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------- apoio

    private ConnectorAccount card() {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user)
                .providerAccountId("card-1").name("Mercado Livre ····9012")
                .institution("Mercado Pago")
                .type(ConnectorAccount.AccountType.CREDIT_CARD)
                .build();
    }

    private ConnectorAccount checking() {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID()).user(user)
                .providerAccountId("acc-1").name("Mercado Pago ····7340")
                .institution("Mercado Pago")
                .type(ConnectorAccount.AccountType.BANK)
                .build();
    }

    private InvoiceReserve reserve(ConnectorAccount cartao, String reference, String amount) {
        return InvoiceReserve.builder()
                .id(UUID.randomUUID()).user(user).cardAccount(cartao)
                .reference(reference).amount(new BigDecimal(amount))
                .build();
    }
}
