package br.com.economize.service.wish;

import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.User;
import br.com.economize.model.WorkProfile;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.repository.WorkProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomeSourceServiceTest {

    private static final String EMAIL = "bia@economize.dev";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private IncomeSourceRepository incomeSourceRepository;
    @Mock
    private WorkProfileRepository workProfileRepository;
    @Mock
    private RecurringSeriesRepository recurringSeriesRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private IncomeSourceService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(USER_ID).name("Bia").email(EMAIL).password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(incomeSourceRepository.findAllByUserIdOrderByKindAscNameAsc(USER_ID)).thenReturn(List.of());
        when(incomeSourceRepository.findByUserIdAndKindAndName(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(incomeSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(workProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recurringSeriesRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
    }

    // ------------------------------------------------- o palpite do tipo

    @Test
    void reconheceVrPelosNomesQueOExtratoUsa() {
        assertThat(IncomeSourceService.guessKind("CREDITO ALELO REFEICAO"))
                .isEqualTo(IncomeSource.Kind.MEAL_VOUCHER);
        assertThat(IncomeSourceService.guessKind("SODEXO"))
                .isEqualTo(IncomeSource.Kind.MEAL_VOUCHER);
        assertThat(IncomeSourceService.guessKind("VALE REFEIÇÃO"))
                .isEqualTo(IncomeSource.Kind.MEAL_VOUCHER);
        assertThat(IncomeSourceService.guessKind("CRED VR 25/08"))
                .isEqualTo(IncomeSource.Kind.MEAL_VOUCHER);
    }

    @Test
    void reconheceSalarioMesmoComAcentoOuAbreviacao() {
        assertThat(IncomeSourceService.guessKind("SALÁRIO")).isEqualTo(IncomeSource.Kind.SALARY);
        assertThat(IncomeSourceService.guessKind("credito folha"))
                .isEqualTo(IncomeSource.Kind.SALARY);
        assertThat(IncomeSourceService.guessKind("PGTO EMPRESA LTDA"))
                .isEqualTo(IncomeSource.Kind.SALARY);
    }

    @Test
    void adiantamentoSalarialGanhaDeSalario() {
        // as duas palavras aparecem; a mais específica é que decide
        assertThat(IncomeSourceService.guessKind("ADIANTAMENTO SALARIAL"))
                .isEqualTo(IncomeSource.Kind.ADVANCE);
    }

    @Test
    void valeAlimentacaoNaoEConfundidoComValeRefeicao() {
        assertThat(IncomeSourceService.guessKind("VALE ALIMENTACAO"))
                .isEqualTo(IncomeSource.Kind.FOOD_VOUCHER);
    }

    @Test
    void siglaCurtaNaoCasaDentroDeOutraPalavra() {
        // "vr" solto casaria em "livro"; a cerca de espaços é o que impede
        assertThat(IncomeSourceService.guessKind("VENDA DE LIVROS"))
                .isEqualTo(IncomeSource.Kind.OTHER);
        assertThat(IncomeSourceService.guessKind("ALUGUEL RECEBIDO"))
                .isEqualTo(IncomeSource.Kind.OTHER);
    }

    // ------------------------------------------------------- sugestões

    @Test
    void sugereApenasRendaRecorrenteQueAindaNaoViraFonte() {
        RecurringSeries salario = series(RecurringSeries.Flow.INCOME, "salario", "Salário", (short) 5);
        RecurringSeries jaAceita = series(RecurringSeries.Flow.INCOME, "alelo", "Alelo", (short) 25);
        RecurringSeries conta = series(RecurringSeries.Flow.EXPENSE, "sabesp", "Sabesp", (short) 10);
        when(recurringSeriesRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(salario, jaAceita, conta));
        // o conjunto vem numa consulta só — série a série era o N+1 que segurava o /income
        when(incomeSourceRepository.findLinkedSeriesIds(USER_ID)).thenReturn(Set.of(jaAceita.getId()));

        WishResponses.IncomeOverview overview = service.overview(EMAIL);

        assertThat(overview.suggestions()).hasSize(1);
        assertThat(overview.suggestions().get(0).name()).isEqualTo("Salário");
        assertThat(overview.suggestions().get(0).suggestedKind()).isEqualTo("SALARY");
        assertThat(overview.suggestions().get(0).anchorDay()).isEqualTo((short) 5);
    }

    @Test
    void rendaIrregularNaoViraSugestao() {
        RecurringSeries freela = series(RecurringSeries.Flow.INCOME, "freela", "Freela", null);
        freela.setCadence(RecurringSeries.Cadence.IRREGULAR);
        when(recurringSeriesRepository.findAllByUserId(USER_ID)).thenReturn(List.of(freela));

        // sem cadência não há âncora, e âncora é o que a fonte existe para guardar
        assertThat(service.overview(EMAIL).suggestions()).isEmpty();
    }

    @Test
    void serieDescartadaNaoRessuscitaComoSugestao() {
        RecurringSeries descartada = series(RecurringSeries.Flow.INCOME, "salario", "Salário", (short) 5);
        descartada.setDismissed(true);
        when(recurringSeriesRepository.findAllByUserId(USER_ID)).thenReturn(List.of(descartada));

        assertThat(service.overview(EMAIL).suggestions()).isEmpty();
    }

    // -------------------------------------------------------- aceitação

    @Test
    void aceitarSugestaoJaNasceConfirmadaEAmarradaNaSerie() {
        RecurringSeries salario = series(RecurringSeries.Flow.INCOME, "salario", "Salário", (short) 5);
        salario.setExpectedAmount(new BigDecimal("4400.00"));
        when(recurringSeriesRepository.findByIdAndUserId(salario.getId(), USER_ID))
                .thenReturn(Optional.of(salario));

        WishResponses.IncomeSourceItem item = service.acceptSuggestion(EMAIL, salario.getId(), null);

        assertThat(item.confirmed()).isTrue();
        assertThat(item.kind()).isEqualTo("SALARY");
        assertThat(item.expectedAmount()).isEqualByComparingTo("4400.00");
        assertThat(item.anchorDay()).isEqualTo((short) 5);
        assertThat(item.seriesId()).isEqualTo(salario.getId());
    }

    @Test
    void aceitarDuasVezesAMesmaSerieNaoDuplicaAFonte() {
        RecurringSeries salario = series(RecurringSeries.Flow.INCOME, "salario", "Salário", (short) 5);
        when(recurringSeriesRepository.findByIdAndUserId(salario.getId(), USER_ID))
                .thenReturn(Optional.of(salario));
        when(incomeSourceRepository.existsByUserIdAndSeriesId(USER_ID, salario.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.acceptSuggestion(EMAIL, salario.getId(), null))
                .isInstanceOf(ResourceConflictException.class);
        verify(incomeSourceRepository, never()).save(any());
    }

    @Test
    void aceiteComNomeJaUsadoGanhaSufixoEmVezDeTravar() {
        RecurringSeries salario = series(RecurringSeries.Flow.INCOME, "salario", "Salário", (short) 5);
        when(recurringSeriesRepository.findByIdAndUserId(salario.getId(), USER_ID))
                .thenReturn(Optional.of(salario));
        when(incomeSourceRepository.findByUserIdAndKindAndName(USER_ID, IncomeSource.Kind.SALARY, "Salário"))
                .thenReturn(Optional.of(IncomeSource.builder().id(UUID.randomUUID()).build()));

        WishResponses.IncomeSourceItem item = service.acceptSuggestion(EMAIL, salario.getId(), null);

        // travar a tela num 409 por causa de um nome repetido seria punir o
        // usuário por ter cadastrado a fonte à mão antes
        assertThat(item.name()).isEqualTo("Salário (2)");
    }

    @Test
    void aceitarSerieDeOutroDonoNaoEncontraNada() {
        UUID alheia = UUID.randomUUID();
        when(recurringSeriesRepository.findByIdAndUserId(alheia, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptSuggestion(EMAIL, alheia, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --------------------------------------------------------- cadastro

    @Test
    void cadastroManualNasceConfirmadoPorqueEDeclaracaoDoUsuario() {
        WishResponses.IncomeSourceItem item = service.create(EMAIL, new WishRequests.CreateIncomeSource(
                "SALARY", "Salário", new BigDecimal("5000"), 5, null));

        assertThat(item.confirmed()).isTrue();
        assertThat(item.active()).isTrue();
        assertThat(item.anchorDay()).isEqualTo((short) 5);
    }

    @Test
    void tipoInvalidoRespondeComAListaDeTiposAceitos() {
        assertThatThrownBy(() -> service.create(EMAIL, new WishRequests.CreateIncomeSource(
                "VALE_QUALQUER", "X", BigDecimal.TEN, 5, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEAL_VOUCHER");
    }

    @Test
    void nomeRepetidoNoMesmoTipoEConflito() {
        when(incomeSourceRepository.findByUserIdAndKindAndName(USER_ID, IncomeSource.Kind.SALARY, "Salário"))
                .thenReturn(Optional.of(IncomeSource.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.create(EMAIL, new WishRequests.CreateIncomeSource(
                "SALARY", "Salário", BigDecimal.TEN, 5, null)))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void editarFonteDeOutroDonoNaoEncontraNada() {
        UUID alheia = UUID.randomUUID();
        when(incomeSourceRepository.findByIdAndUserId(alheia, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(EMAIL, alheia,
                new WishRequests.UpdateIncomeSource("X", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renomearParaOProprioNomeNaoAcusaConflito() {
        UUID id = UUID.randomUUID();
        IncomeSource existente = IncomeSource.builder()
                .id(id).user(user).kind(IncomeSource.Kind.SALARY).name("Salário")
                .confirmed(true).active(true).build();
        when(incomeSourceRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));
        when(incomeSourceRepository.findByUserIdAndKindAndName(USER_ID, IncomeSource.Kind.SALARY, "Salário"))
                .thenReturn(Optional.of(existente));

        WishResponses.IncomeSourceItem item = service.update(EMAIL, id,
                new WishRequests.UpdateIncomeSource("Salário", null, null, null, null));

        assertThat(item.name()).isEqualTo("Salário");
    }

    // ---------------------------------------------------------- jornada

    @Test
    void jornadaVirouHorasPorMes() {
        WishResponses.WorkProfileItem item = service.saveWorkProfile(EMAIL,
                new WishRequests.SaveWorkProfile(5, new BigDecimal("8")));

        // 8 x 5 x 52/12
        assertThat(item.hoursPerMonth()).isEqualByComparingTo("173.33");
        assertThat(item.daysPerWeek()).isEqualTo(5);
    }

    @Test
    void jornadaSalvaDuasVezesSubstituiEmVezDeDuplicar() {
        WorkProfile existente = WorkProfile.builder()
                .userId(USER_ID).daysPerWeek((short) 5).hoursPerDay(new BigDecimal("8")).build();
        when(workProfileRepository.findById(USER_ID)).thenReturn(Optional.of(existente));

        service.saveWorkProfile(EMAIL, new WishRequests.SaveWorkProfile(6, new BigDecimal("6.5")));

        ArgumentCaptor<WorkProfile> captor = ArgumentCaptor.forClass(WorkProfile.class);
        verify(workProfileRepository).save(captor.capture());
        // a chave primária é o usuário: salvar de novo é UPDATE, nunca INSERT
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getDaysPerWeek()).isEqualTo((short) 6);
        assertThat(captor.getValue().getHoursPerDay()).isEqualByComparingTo("6.5");
    }

    @Test
    void panoramaSemJornadaDevolveNuloEmVezDeUmPadraoInventado() {
        assertThat(service.overview(EMAIL).workProfile()).isNull();
    }

    private RecurringSeries series(RecurringSeries.Flow flow, String key, String label, Short anchorDay) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID())
                .user(user)
                .merchantKey(key)
                .displayName(label)
                .flow(flow)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .anchorDay(anchorDay)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }
}
