package br.com.economize.service;

import br.com.economize.config.PlanProperties;
import br.com.economize.dto.plan.PlansResponse;
import br.com.economize.model.Plan;
import br.com.economize.model.PlanInterest;
import br.com.economize.model.User;
import br.com.economize.repository.PlanInterestRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    private static final String EMAIL = "teste@economize.app";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlanInterestRepository interestRepository;

    private PlanService service;
    private User user;

    @BeforeEach
    void setUp() {
        // defaults de código: é o que a instalação sem PLAN_* anuncia
        service = new PlanService(userRepository, interestRepository, new PlanProperties());
        user = User.builder().id(UUID.randomUUID()).name("Teste").email(EMAIL).password("x").build();
        // lenient: o caso "usuário desconhecido" não consulta este e-mail
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Conta nova é FREE: oferta com os dois planos, preço padrão do Plus e sem checkout")
    void contaNovaEFree() {
        when(interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS)).thenReturn(false);

        PlansResponse response = service.describe(EMAIL);

        assertThat(response.current()).isEqualTo(Plan.FREE);
        assertThat(response.checkoutAvailable()).isFalse();
        assertThat(response.interestRegistered()).isFalse();
        assertThat(response.plans()).extracting(PlansResponse.PlanOption::id).containsExactly(Plan.FREE, Plan.PLUS);
        PlansResponse.PlanOption plus = response.plans().get(1);
        assertThat(plus.name()).isEqualTo("Economize! Plus");
        assertThat(plus.priceMonthly()).isEqualByComparingTo(new BigDecimal("9.90"));
        assertThat(plus.features()).containsExactly("Sem anúncios", "Conexão bancária ilimitada",
                "Relatórios em PDF", "Prioridade no assistente");
        assertThat(response.plans().get(0).priceMonthly()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PLUS sem prazo é vigente; PLUS vencido volta a ser FREE para quem pergunta")
    void vigenciaDoPlus() {
        user.setPlan(Plan.PLUS);
        assertThat(service.describe(EMAIL).current()).isEqualTo(Plan.PLUS);

        user.setPlanUntil(OffsetDateTime.now().plusDays(1));
        assertThat(service.describe(EMAIL).current()).isEqualTo(Plan.PLUS);

        user.setPlanUntil(OffsetDateTime.now().minusMinutes(1));
        assertThat(service.describe(EMAIL).current())
                .as("vencido: a coluna continua PLUS, a oferta trata como FREE").isEqualTo(Plan.FREE);
    }

    @Test
    @DisplayName("Interesse já registrado aparece na oferta, e as properties sobrescrevem preço e vantagens")
    void interesseEPropertiesRefletidos() {
        when(interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS)).thenReturn(true);
        PlanProperties properties = new PlanProperties();
        properties.getPlus().setPriceMonthly(new BigDecimal("12.90"));
        properties.getPlus().setFeatures(java.util.List.of("Sem anúncios"));
        properties.setCheckoutAvailable(true);
        service = new PlanService(userRepository, interestRepository, properties);

        PlansResponse response = service.describe(EMAIL);

        assertThat(response.interestRegistered()).isTrue();
        assertThat(response.checkoutAvailable()).isTrue();
        assertThat(response.plans().get(1).priceMonthly()).isEqualByComparingTo("12.90");
        assertThat(response.plans().get(1).features()).containsExactly("Sem anúncios");
    }

    @Test
    @DisplayName("Registrar interesse grava (usuário, plano) uma vez; repetir não grava de novo")
    void registroIdempotente() {
        when(interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS)).thenReturn(false, true);

        service.registerInterest(EMAIL, Plan.PLUS);
        service.registerInterest(EMAIL, Plan.PLUS);

        ArgumentCaptor<PlanInterest> saved = ArgumentCaptor.forClass(PlanInterest.class);
        verify(interestRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getPlan()).isEqualTo(Plan.PLUS);
    }

    @Test
    @DisplayName("Duplo toque simultâneo: o unique estoura no flush e o segundo pedido termina bem")
    void corridaTerminaBem() {
        when(interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS)).thenReturn(false);
        when(interestRepository.saveAndFlush(any(PlanInterest.class)))
                .thenThrow(new DataIntegrityViolationException("uq_plan_interest_user_plan"));

        service.registerInterest(EMAIL, Plan.PLUS);
        // sem exceção: para quem chamou, o interesse está registrado
    }

    @Test
    @DisplayName("Usuário desconhecido responde 400, sem tocar no repositório de interesse")
    void usuarioDesconhecido() {
        when(userRepository.findByEmail("x@y")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerInterest("x@y", Plan.PLUS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.describe("x@y"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(interestRepository, never()).saveAndFlush(any());
    }
}
