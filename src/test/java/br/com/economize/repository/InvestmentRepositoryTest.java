package br.com.economize.repository;

import br.com.economize.model.InvestmentInterest;
import br.com.economize.model.InvestmentPosition;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As duas tabelas da V24 contra banco: posições e interesses.
 *
 * <p>Duas consultas daqui decidem coisas visíveis — a leitura por
 * {@code (id, dono)} é o que faz posição alheia responder 404, e a chave
 * {@code (dono, origem, id no provedor)} é o alvo do upsert do sync. O que o
 * índice parcial da V24 garante em produção (duas manuais SEM id de provedor
 * coexistem) precisa continuar verdadeiro no mapeamento JPA: um
 * {@code @UniqueConstraint} na entidade quebraria o cadastro manual no H2 e
 * ninguém veria antes do deploy.
 */
@DataJpaTest
@DisplayName("Posições e interesses de investimento contra banco")
class InvestmentRepositoryTest {

    @Autowired
    private InvestmentPositionRepository positionRepository;

    @Autowired
    private InvestmentInterestRepository interestRepository;

    @Autowired
    private UserRepository userRepository;

    private User dono;
    private User estranho;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        estranho = userRepository.save(user("estranho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private InvestmentPosition posicao(User owner, InvestmentPosition.Source source, String providerId,
                                       String nome, InvestmentPosition.Type type) {
        return positionRepository.save(InvestmentPosition.builder()
                .user(owner)
                .source(source)
                .providerPositionId(providerId)
                .name(nome)
                .type(type)
                .investedAmount(new BigDecimal("1000.0000"))
                .build());
    }

    // ------------------------------------------------------------ posições

    @Test
    @DisplayName("A posição de outro usuário não é alcançável por id")
    void posicaoAlheiaNaoEAlcancavel() {
        InvestmentPosition alheia = posicao(estranho, InvestmentPosition.Source.MANUAL, null,
                "VT", InvestmentPosition.Type.ETF);

        assertThat(positionRepository.findByIdAndUserId(alheia.getId(), dono.getId())).isEmpty();
        assertThat(positionRepository.findByIdAndUserId(alheia.getId(), estranho.getId())).isPresent();
    }

    @Test
    @DisplayName("A listagem é só do dono, em ordem alfabética")
    void listagemPorDono() {
        posicao(dono, InvestmentPosition.Source.MANUAL, null, "Tesouro Selic 2029", InvestmentPosition.Type.TREASURY);
        posicao(dono, InvestmentPosition.Source.MANUAL, null, "CDB Inter", InvestmentPosition.Type.FIXED_INCOME);
        posicao(estranho, InvestmentPosition.Source.MANUAL, null, "Barco", InvestmentPosition.Type.OTHER);

        assertThat(positionRepository.findAllByUserIdOrderByNameAsc(dono.getId()))
                .extracting(InvestmentPosition::getName)
                .containsExactly("CDB Inter", "Tesouro Selic 2029");
    }

    @Test
    @DisplayName("Duas posições MANUAIS sem id de provedor coexistem — o único é parcial")
    void duasManuaisSemProviderIdCoexistem() {
        posicao(dono, InvestmentPosition.Source.MANUAL, null, "VT", InvestmentPosition.Type.ETF);
        posicao(dono, InvestmentPosition.Source.MANUAL, null, "CDB Mercado Pago", InvestmentPosition.Type.FIXED_INCOME);
        positionRepository.flush();

        // Se a entidade carregasse um @UniqueConstraint sobre (user, source,
        // provider_position_id), o H2 recusaria a segunda manual aqui — e o
        // cadastro manual, que é o único caminho da ETF no exterior, morreria
        assertThat(positionRepository.countByUserIdAndSource(dono.getId(), InvestmentPosition.Source.MANUAL))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("O alvo do upsert é (dono, origem, id no provedor) — nunca só o id")
    void alvoDoUpsertPorDono() {
        posicao(dono, InvestmentPosition.Source.CONNECTOR, "pluggy-inv-1", "CDB Inter", InvestmentPosition.Type.FIXED_INCOME);
        posicao(estranho, InvestmentPosition.Source.CONNECTOR, "pluggy-inv-1", "CDB dele", InvestmentPosition.Type.FIXED_INCOME);

        assertThat(positionRepository.findByUserIdAndSourceAndProviderPositionId(
                dono.getId(), InvestmentPosition.Source.CONNECTOR, "pluggy-inv-1"))
                .get().extracting(InvestmentPosition::getName).isEqualTo("CDB Inter");
        // a mesma chave, em outra ORIGEM, é outra posição: uma STATEMENT que
        // um dia reaproveite o id não pode ser confundida com a do conector
        assertThat(positionRepository.findByUserIdAndSourceAndProviderPositionId(
                dono.getId(), InvestmentPosition.Source.STATEMENT, "pluggy-inv-1")).isEmpty();
    }

    @Test
    @DisplayName("A posição nasce com moeda BRL e datas sem ninguém preencher")
    void posicaoNasceComDefaults() {
        InvestmentPosition vt = posicao(dono, InvestmentPosition.Source.MANUAL, null, "VT", InvestmentPosition.Type.ETF);

        assertThat(vt.getCurrency()).isEqualTo("BRL");
        assertThat(vt.getCreatedAt()).isNotNull();
        assertThat(vt.getUpdatedAt()).isNotNull();
        // valor atual ausente sobrevive como NULO, não vira zero
        assertThat(positionRepository.findById(vt.getId()).orElseThrow().getCurrentValue()).isNull();
    }

    // ---------------------------------------------------------- interesses

    @Test
    @DisplayName("O mesmo interesse não entra duas vezes para o mesmo dono")
    void interesseDuplicadoEBarrado() {
        interestRepository.saveAndFlush(interesse(dono, InvestmentInterest.Kind.CURRENCY, "USD"));

        assertThatThrownBy(() -> interestRepository.saveAndFlush(
                interesse(dono, InvestmentInterest.Kind.CURRENCY, "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("O interesse do vizinho não colide com o meu nem aparece para mim")
    void interesseEPorDono() {
        interestRepository.save(interesse(estranho, InvestmentInterest.Kind.CURRENCY, "USD"));
        interestRepository.save(interesse(dono, InvestmentInterest.Kind.RATE, "CDI"));
        // mesmo código em OUTRO tipo é outro interesse
        interestRepository.save(interesse(dono, InvestmentInterest.Kind.TOPIC, "cambio"));
        interestRepository.saveAndFlush(interesse(dono, InvestmentInterest.Kind.CURRENCY, "USD"));

        List<InvestmentInterest> meus = interestRepository.findAllByUserIdOrderByCreatedAtAsc(dono.getId());
        assertThat(meus).extracting(InvestmentInterest::getCode).containsExactly("CDI", "cambio", "USD");

        assertThat(interestRepository.findByUserIdAndKindAndCode(
                dono.getId(), InvestmentInterest.Kind.CURRENCY, "USD")).isPresent();
        assertThat(interestRepository.findByUserIdAndKindAndCode(
                estranho.getId(), InvestmentInterest.Kind.RATE, "CDI")).isEmpty();
    }

    private InvestmentInterest interesse(User owner, InvestmentInterest.Kind kind, String code) {
        return InvestmentInterest.builder().user(owner).kind(kind).code(code).build();
    }
}
