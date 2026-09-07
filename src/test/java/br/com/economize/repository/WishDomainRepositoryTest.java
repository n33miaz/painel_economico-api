package br.com.economize.repository;

import br.com.economize.model.IncomeSource;
import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.model.WorkProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As três tabelas da V18 contra banco (EC-117): desejos, fontes de renda e
 * jornada.
 *
 * <p>São as mais novas do projeto e as que menos rodaram: nasceram nesta rodada
 * e todo teste delas até agora dublou o repositório. Duas consultas daqui
 * decidem coisas visíveis — {@code findAllByUserIdAndActiveTrue} alimenta as
 * ressalvas do mês (EC-138) e o pedido de extrato do VR (EC-137), e a chave
 * {@code (usuário, tipo, nome)} é o que impede a mesma fonte de entrar duas
 * vezes.
 */
@DataJpaTest
@DisplayName("Desejos, renda e jornada contra banco (EC-117)")
class WishDomainRepositoryTest {

    @Autowired
    private WishRepository wishRepository;

    @Autowired
    private IncomeSourceRepository incomeSourceRepository;

    @Autowired
    private WorkProfileRepository workProfileRepository;

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

    private Wish desejo(User owner, String nome, String alvo, Wish.Status status) {
        return wishRepository.save(Wish.builder()
                .user(owner)
                .name(nome)
                .targetAmount(new BigDecimal(alvo))
                .savedAmount(BigDecimal.ZERO)
                .status(status)
                .build());
    }

    private IncomeSource fonte(User owner, IncomeSource.Kind kind, String nome,
                               Short ancora, boolean ativa) {
        return incomeSourceRepository.save(IncomeSource.builder()
                .user(owner)
                .kind(kind)
                .name(nome)
                .expectedAmount(new BigDecimal("600.00"))
                .anchorDay(ancora)
                .confirmed(true)
                .active(ativa)
                .build());
    }

    // ------------------------------------------------------------- desejos

    @Test
    @DisplayName("O desejo de outro usuário não é alcançável por id")
    void desejoAlheioNaoEAlcancavel() {
        Wish alheio = desejo(estranho, "Moto", "18000", Wish.Status.WISH);

        assertThat(wishRepository.findByIdAndUserId(alheio.getId(), dono.getId())).isEmpty();
    }

    @Test
    @DisplayName("A lista de desejos é só do dono, e a contagem também")
    void listaEContagemPorDono() {
        desejo(dono, "Moto", "18000", Wish.Status.WISH);
        desejo(dono, "Casa", "320000", Wish.Status.GOAL);
        desejo(estranho, "Barco", "90000", Wish.Status.WISH);

        assertThat(wishRepository.findAllByUserIdOrderByCreatedAtDesc(dono.getId())).hasSize(2);
        assertThat(wishRepository.countByUserId(dono.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("O filtro por status separa quem compete pela sobra do mês")
    void filtroPorStatus() {
        desejo(dono, "Moto", "18000", Wish.Status.WISH);
        desejo(dono, "Casa", "320000", Wish.Status.GOAL);
        desejo(dono, "Fone", "380", Wish.Status.PURCHASED);
        desejo(dono, "Antigo", "100", Wish.Status.ARCHIVED);

        List<Wish> vivos = wishRepository.findAllByUserIdAndStatusIn(
                dono.getId(), List.of(Wish.Status.WISH, Wish.Status.GOAL));

        // Só WISH e GOAL estão em curso: comprado e arquivado voltarem aqui
        // faria a projeção disputar a sobra com desejo que já acabou
        assertThat(vivos).extracting(Wish::getName)
                .containsExactlyInAnyOrder("Moto", "Casa");
    }

    @Test
    @DisplayName("O desejo nasce com data de criação sem ninguém preencher")
    void desejoNasceComData() {
        Wish moto = desejo(dono, "Moto", "18000", Wish.Status.WISH);

        // A ordenação da lista depende disso. Nulo aqui deixaria a ordem ao
        // acaso do banco, e o @PrePersist nunca havia rodado em teste
        assertThat(moto.getCreatedAt()).isNotNull();
        assertThat(moto.getUpdatedAt()).isNotNull();
    }

    // -------------------------------------------------------------- renda

    @Test
    @DisplayName("Só as fontes ATIVAS do dono alimentam ressalva e pedido de extrato")
    void fontesAtivasDoDono() {
        fonte(dono, IncomeSource.Kind.SALARY, "Salário", (short) 5, true);
        fonte(dono, IncomeSource.Kind.MEAL_VOUCHER, "Vale-refeição", (short) 25, true);
        fonte(dono, IncomeSource.Kind.OTHER, "Freela antigo", null, false);
        fonte(estranho, IncomeSource.Kind.SALARY, "Salário dele", (short) 1, true);

        List<IncomeSource> ativas = incomeSourceRepository.findAllByUserIdAndActiveTrue(dono.getId());

        // A inativa de volta reviveria uma ressalva sobre dinheiro que não
        // entra mais; a do vizinho inventaria uma renda que não existe
        assertThat(ativas).extracting(IncomeSource::getName)
                .containsExactlyInAnyOrder("Salário", "Vale-refeição");
    }

    @Test
    @DisplayName("A âncora nula sobrevive à ida e volta do banco")
    void ancoraNulaSobrevive() {
        IncomeSource semAncora = fonte(dono, IncomeSource.Kind.OTHER, "Freela", null, true);

        assertThat(incomeSourceRepository.findById(semAncora.getId()))
                .get().extracting(IncomeSource::getAnchorDay).isNull();
    }

    @Test
    @DisplayName("A colisão de cadastro é por usuário, tipo e nome")
    void colisaoPorUsuarioTipoNome() {
        fonte(dono, IncomeSource.Kind.SALARY, "Salário", (short) 5, true);

        assertThat(incomeSourceRepository.findByUserIdAndKindAndName(
                dono.getId(), IncomeSource.Kind.SALARY, "Salário")).isPresent();
        // Mesmo nome em tipo diferente é outra fonte: quem tem dois vínculos
        // registra "Salário" duas vezes, uma como salário e outra como outra renda
        assertThat(incomeSourceRepository.findByUserIdAndKindAndName(
                dono.getId(), IncomeSource.Kind.OTHER, "Salário")).isEmpty();
        // E o nome do vizinho nunca colide com o meu
        assertThat(incomeSourceRepository.findByUserIdAndKindAndName(
                estranho.getId(), IncomeSource.Kind.SALARY, "Salário")).isEmpty();
    }

    @Test
    @DisplayName("A série já aproveitada não volta a virar sugestão")
    void serieUsadaEIdempotente() {
        UUID serie = UUID.randomUUID();
        IncomeSource daSerie = fonte(dono, IncomeSource.Kind.SALARY, "Salário", (short) 5, true);
        daSerie.setSeriesId(serie);
        incomeSourceRepository.saveAndFlush(daSerie);

        assertThat(incomeSourceRepository.existsByUserIdAndSeriesId(dono.getId(), serie)).isTrue();
        // Para o vizinho a mesma série é desconhecida: a chave de idempotência
        // é o par (usuário, série), nunca a série sozinha
        assertThat(incomeSourceRepository.existsByUserIdAndSeriesId(estranho.getId(), serie)).isFalse();
        // O conjunto inteiro numa consulta responde o mesmo que a pergunta série a
        // série — e fonte sem série (cadastro manual) não polui o conjunto
        assertThat(incomeSourceRepository.findLinkedSeriesIds(dono.getId())).containsExactly(serie);
        assertThat(incomeSourceRepository.findLinkedSeriesIds(estranho.getId())).isEmpty();
    }

    @Test
    @DisplayName("A fonte de outro usuário não é editável por id")
    void fonteAlheiaNaoEEditavel() {
        IncomeSource alheia = fonte(estranho, IncomeSource.Kind.SALARY, "Salário dele", (short) 1, true);

        assertThat(incomeSourceRepository.findByIdAndUserId(alheia.getId(), dono.getId())).isEmpty();
    }

    // ------------------------------------------------------------ jornada

    @Test
    @DisplayName("A jornada é uma por usuário: o segundo save substitui")
    void jornadaEUmaPorUsuario() {
        workProfileRepository.save(WorkProfile.builder()
                .userId(dono.getId()).daysPerWeek((short) 5)
                .hoursPerDay(new BigDecimal("8.00")).build());
        workProfileRepository.save(WorkProfile.builder()
                .userId(dono.getId()).daysPerWeek((short) 6)
                .hoursPerDay(new BigDecimal("6.50")).build());

        // A PK é o próprio usuário, então PUT cria ou substitui — duas linhas
        // aqui dariam duas verdades sobre a mesma jornada
        assertThat(workProfileRepository.count()).isEqualTo(1);
        assertThat(workProfileRepository.findById(dono.getId()))
                .get().extracting(WorkProfile::getDaysPerWeek).isEqualTo((short) 6);
    }

    @Test
    @DisplayName("Meia hora sobrevive: 6h30 é 6.50, não 6")
    void meiaHoraSobrevive() {
        workProfileRepository.save(WorkProfile.builder()
                .userId(dono.getId()).daysPerWeek((short) 5)
                .hoursPerDay(new BigDecimal("6.50")).build());

        // Truncar aqui mudaria o valor da hora do usuário, e com ele o custo em
        // horas de todo desejo
        assertThat(workProfileRepository.findById(dono.getId()).orElseThrow().getHoursPerDay())
                .isEqualByComparingTo("6.50");
    }

    @Test
    @DisplayName("A jornada de um usuário não aparece para o outro")
    void jornadaNaoVaza() {
        workProfileRepository.save(WorkProfile.builder()
                .userId(dono.getId()).daysPerWeek((short) 5)
                .hoursPerDay(new BigDecimal("8.00")).build());

        assertThat(workProfileRepository.findById(estranho.getId())).isEmpty();
    }
}
