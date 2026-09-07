package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvestmentMovementService — os movimentos de investimento lidos do extrato")
class InvestmentMovementServiceTest {

    private static final String EMAIL = "teste@economize.app";
    private static final UUID INVESTIMENTOS = UUID.fromString("c0000000-0000-4000-8000-000000000012");
    private static final UUID ALIMENTACAO = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID CONTA_INTER = UUID.randomUUID();

    @Mock
    private BankTransactionRepository transactionRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ConnectorAccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;

    private InvestmentMovementService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        Category investimentos = Category.builder().id(INVESTIMENTOS).slug("investimentos").systemKey("INVESTMENT").build();
        Category alimentacao = Category.builder().id(ALIMENTACAO).slug("alimentacao").systemKey("FOOD").build();
        // subcategoria do usuário dentro de Investimentos e categoria própria
        // com "invest" no slug: as duas contam como investimento
        Category tesouroSub = Category.builder().id(UUID.randomUUID()).slug("tesouro").parent(investimentos).build();
        Category exterior = Category.builder().id(UUID.randomUUID()).slug("investimentos-exterior").build();
        when(categoryRepository.findVisibleTo(user.getId()))
                .thenReturn(List.of(investimentos, alimentacao, tesouroSub, exterior));

        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(
                ConnectorAccount.builder().id(CONTA_INTER).user(user).name("Conta ····1234")
                        .institution("Banco Inter").type(ConnectorAccount.AccountType.BANK).build()));

        service = new InvestmentMovementService(transactionRepository, categoryRepository, accountRepository,
                userRepository, 12);
    }

    private BankTransaction tx(String descricao, String valor, UUID categoryId, String legacy, UUID accountId,
                               boolean internal) {
        BigDecimal amount = new BigDecimal(valor);
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type(amount.signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(amount)
                .description(descricao)
                .categoryId(categoryId)
                .category(legacy)
                .accountId(accountId)
                .internalTransfer(internal)
                .date(OffsetDateTime.now(ZoneOffset.UTC).minusDays(3))
                .build();
    }

    @Test
    @DisplayName("classifica APPLY, REDEEM, YIELD e OTHER e soma os totais em valor absoluto")
    void classificaMovimentos() {
        when(transactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                eq(user.getId()), any(), any())).thenReturn(List.of(
                tx("Aplicação CDB Inter", "-1000.00", INVESTIMENTOS, "INVESTMENT", CONTA_INTER, false),
                tx("Resgate CDB Inter", "500.00", INVESTIMENTOS, "INVESTMENT", CONTA_INTER, false),
                tx("Rendimentos", "12.34", INVESTIMENTOS, "INVESTMENT", null, false),
                // sem categoria: entra pelo TEXTO ("cdb"), e débito é aplicação
                tx("CDB Mercado Pago", "-200.00", null, null, null, false),
                // débito com texto de rendimento é ajuste (IR), não aplicação
                tx("IR sobre rendimentos", "-3.00", INVESTIMENTOS, "INVESTMENT", null, false),
                // categoria legada como string, sem category_id: também conta
                tx("Tesouro Direto compra", "-300.00", null, "INVESTMENT", null, false),
                // "aplicativo" NÃO é aplicação: fronteira de palavra
                tx("COMPRA APLICATIVO UBER", "-30.00", ALIMENTACAO, "FOOD", null, false),
                tx("PIX MERCADO CENTRAL", "-50.00", null, null, null, false),
                // perna interna fica fora seja qual for o texto
                tx("Resgate", "700.00", INVESTIMENTOS, "INVESTMENT", null, true)));

        InvestmentResponses.Movements m = service.movements(EMAIL, 12);

        assertThat(m.movements()).hasSize(6);
        assertThat(m.movements()).extracting(InvestmentResponses.MovementRow::kind)
                .containsExactly("APPLY", "REDEEM", "YIELD", "APPLY", "OTHER", "APPLY");
        assertThat(m.totals().applied()).isEqualByComparingTo("1500.00");
        assertThat(m.totals().redeemed()).isEqualByComparingTo("500.00");
        assertThat(m.totals().yield()).isEqualByComparingTo("12.34");
        assertThat(m.totals().other()).isEqualByComparingTo("3.00");
        assertThat(m.netInvested()).isEqualByComparingTo("1000.00");

        InvestmentResponses.MovementRow aplicacao = m.movements().get(0);
        // o valor mantém o SINAL do extrato; a instituição vem da conta de origem
        assertThat(aplicacao.amount()).isEqualByComparingTo("-1000.00");
        assertThat(aplicacao.institution()).isEqualTo("Banco Inter");
        assertThat(aplicacao.accountId()).isEqualTo(CONTA_INTER);
        assertThat(aplicacao.description()).isEqualTo("Aplicação CDB Inter");
        assertThat(m.movements().get(2).institution()).isNull();
    }

    @Test
    @DisplayName("subcategoria de Investimentos e categoria própria com 'invest' no slug elegem o lançamento")
    void categoriasDerivadasContam() {
        UUID tesouroSub = categoryRepository.findVisibleTo(user.getId()).get(2).getId();
        UUID exterior = categoryRepository.findVisibleTo(user.getId()).get(3).getId();
        when(transactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                eq(user.getId()), any(), any())).thenReturn(List.of(
                tx("TD compra", "-100.00", tesouroSub, "tesouro", null, false),
                tx("Avenue transfer", "-2000.00", exterior, "investimentos-exterior", null, false),
                tx("Padaria", "-20.00", ALIMENTACAO, "FOOD", null, false)));

        InvestmentResponses.Movements m = service.movements(EMAIL, null);

        assertThat(m.movements()).hasSize(2);
        assertThat(m.movements()).allMatch(row -> "APPLY".equals(row.kind()));
        // sem ?months= vale o default da instalação
        assertThat(m.months()).isEqualTo(12);
    }

    @Test
    @DisplayName("a janela é de meses de calendário, o atual incluído, e fora da faixa é 400")
    void janelaDeMeses() {
        when(transactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                eq(user.getId()), any(), any())).thenReturn(List.of());

        InvestmentResponses.Movements m = service.movements(EMAIL, 3);

        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        assertThat(m.to()).isEqualTo(hoje);
        assertThat(m.from()).isEqualTo(hoje.withDayOfMonth(1).minusMonths(2));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(transactionRepository).findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                eq(user.getId()), start.capture(), end.capture());
        assertThat(start.getValue().toLocalDate()).isEqualTo(m.from());
        // o fim é EXCLUSIVO no dia seguinte, para o lançamento de hoje entrar
        assertThat(end.getValue().toLocalDate()).isEqualTo(hoje.plusDays(1));

        assertThatThrownBy(() -> service.movements(EMAIL, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.movements(EMAIL, 121)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("crédito com texto de aplicação é estorno (OTHER); crédito de CDB sem palavra nenhuma é resgate")
    void creditosAmbiguos() {
        assertThat(InvestmentMovementService.classify(
                tx("Aplicação cancelada", "100.00", INVESTIMENTOS, "INVESTMENT", null, false), "aplicacao cancelada"))
                .isEqualTo("OTHER");
        assertThat(InvestmentMovementService.classify(
                tx("CDB Mercado Pago", "100.00", null, null, null, false), "cdb mercado pago"))
                .isEqualTo("REDEEM");
        assertThat(InvestmentMovementService.classify(
                tx("Dividendos PETR4", "35.00", null, null, null, false), "dividendos petr4"))
                .isEqualTo("YIELD");
        assertThat(InvestmentMovementService.classify(
                tx("Estorno de resgate", "-100.00", INVESTIMENTOS, "INVESTMENT", null, false), "estorno de resgate"))
                .isEqualTo("OTHER");
    }
}
