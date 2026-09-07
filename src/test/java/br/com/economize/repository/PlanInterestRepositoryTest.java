package br.com.economize.repository;

import br.com.economize.model.Plan;
import br.com.economize.model.PlanInterest;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O plano na conta e o interesse registrado (V23), contra o schema que o
 * mapeamento JPA monta no H2.
 *
 * <p>O unique (user_id, plan) está declarado na entidade justamente para este
 * teste alcançá-lo: em produção ele vem da migration, mas o que torna o POST
 * /plans/interest idempotente sob corrida é a constraint, e a constraint
 * precisa ser provada, não presumida.
 */
@DataJpaTest
@DisplayName("Plano da conta e interesse (V23)")
class PlanInterestRepositoryTest {

    @Autowired
    private PlanInterestRepository interestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User dono;
    private User vizinho;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        vizinho = userRepository.save(user("vizinho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    @Test
    @DisplayName("Conta nova nasce FREE, sem prazo, e vê anúncios")
    void contaNovaNasceFree() {
        em.flush();
        em.clear();

        User lido = userRepository.findById(dono.getId()).orElseThrow();

        assertThat(lido.getPlan()).isEqualTo(Plan.FREE);
        assertThat(lido.getPlanUntil()).isNull();
        assertThat(lido.isPlus()).isFalse();
    }

    @Test
    @DisplayName("PLUS gravado com prazo futuro é vigente; com prazo passado, não")
    void vigenciaDoPlusPersistida() {
        dono.setPlan(Plan.PLUS);
        dono.setPlanUntil(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
        vizinho.setPlan(Plan.PLUS);
        vizinho.setPlanUntil(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        userRepository.saveAll(java.util.List.of(dono, vizinho));
        em.flush();
        em.clear();

        assertThat(userRepository.findById(dono.getId()).orElseThrow().isPlus()).isTrue();
        User vencido = userRepository.findById(vizinho.getId()).orElseThrow();
        // a coluna continua PLUS (histórico); a vigência é que acabou
        assertThat(vencido.getPlan()).isEqualTo(Plan.PLUS);
        assertThat(vencido.isPlus()).isFalse();
    }

    @Test
    @DisplayName("Interesse gravado é encontrado pelo par (usuário, plano) — e só por ele")
    void interessePorUsuarioEPlano() {
        interestRepository.saveAndFlush(PlanInterest.builder().user(dono).plan(Plan.PLUS).build());

        assertThat(interestRepository.existsByUserIdAndPlan(dono.getId(), Plan.PLUS)).isTrue();
        assertThat(interestRepository.existsByUserIdAndPlan(dono.getId(), Plan.FREE)).isFalse();
        assertThat(interestRepository.existsByUserIdAndPlan(vizinho.getId(), Plan.PLUS)).isFalse();
    }

    @Test
    @DisplayName("O carimbo de quando foi registrado é preenchido na gravação")
    void carimboDeCriacao() {
        PlanInterest salvo = interestRepository.saveAndFlush(
                PlanInterest.builder().user(dono).plan(Plan.PLUS).build());
        em.clear();

        assertThat(interestRepository.findById(salvo.getId()).orElseThrow().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("O mesmo usuário não registra o mesmo plano duas vezes: o unique decide a corrida")
    void uniquePorUsuarioEPlano() {
        interestRepository.saveAndFlush(PlanInterest.builder().user(dono).plan(Plan.PLUS).build());

        assertThatThrownBy(() -> interestRepository.saveAndFlush(
                PlanInterest.builder().user(dono).plan(Plan.PLUS).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Outro usuário registra o mesmo plano normalmente: a unicidade é por dono")
    void uniqueNaoAtravessaUsuarios() {
        interestRepository.saveAndFlush(PlanInterest.builder().user(dono).plan(Plan.PLUS).build());
        interestRepository.saveAndFlush(PlanInterest.builder().user(vizinho).plan(Plan.PLUS).build());

        assertThat(interestRepository.count()).isEqualTo(2);
    }
}
