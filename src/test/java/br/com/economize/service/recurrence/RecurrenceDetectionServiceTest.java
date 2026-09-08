package br.com.economize.service.recurrence;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.RecurringSeriesLink;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixtures 100% sintéticas (nomes fictícios, valores inventados), inspiradas
 * apenas nos PADRÕES textuais que a engine precisa cobrir: rotulagem que muda,
 * adquirente instável, cadência cruzando a virada do mês, valor variável,
 * transferência interna e renda informal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecurrenceDetectionServiceTest {

    private static final String EMAIL = "carlos@economize.dev";

    @Mock
    private UserRepository userRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private RecurringSeriesRepository seriesRepository;

    @Mock
    private RecurringSeriesLinkRepository linkRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private RecurrenceDetectionService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Carlos Pereira").email(EMAIL).password("x").build();

    private final UUID utilitiesCategory = UUID.randomUUID();

    // "banco" em memória: os mocks leem e escrevem aqui, o que permite testar
    // idempotência e incrementos com o mesmo código de produção
    private final List<BankTransaction> transactions = new ArrayList<>();
    private final List<RecurringSeries> seriesStore = new ArrayList<>();
    private final List<RecurringSeriesLink> linkStore = new ArrayList<>();

    @BeforeEach
    void wireStores() {
        // o template roda o callback direto: a transação real não existe no teste
        // unitário, e os cenários de corrida a substituem para simular o commit
        // perdido (ver retriesInFreshTransaction...)
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(new SimpleTransactionStatus()));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenAnswer(inv -> new ArrayList<>(transactions));
        when(seriesRepository.findAllByUserId(user.getId()))
                .thenAnswer(inv -> new ArrayList<>(seriesStore));
        when(seriesRepository.save(any(RecurringSeries.class))).thenAnswer(inv -> {
            RecurringSeries series = inv.getArgument(0);
            if (series.getId() == null) {
                series.setId(UUID.randomUUID());
                seriesStore.add(series);
            }
            return series;
        });
        when(seriesRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(linkRepository.findAllBySeriesIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return linkStore.stream().filter(link -> ids.contains(link.getSeriesId())).toList();
        });
        when(linkRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<RecurringSeriesLink> links = inv.getArgument(0);
            linkStore.addAll(links);
            return links;
        });
        when(linkRepository.deleteAllBySeriesIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            int before = linkStore.size();
            linkStore.removeIf(link -> ids.contains(link.getSeriesId()));
            return before - linkStore.size();
        });
    }

    @Test
    void firstRunDetectsAllRecurrenceSignals() {
        loadCoreFixtures();

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(7);
        assertThat(summary.seriesUpdated()).isZero();
        assertThat(summary.linksCreated()).isEqualTo(31);
        assertThat(linkStore).hasSize(31);
        assertThat(linkStore.stream().map(RecurringSeriesLink::getBankTransactionId).distinct()).hasSize(31);

        // 1) rotulagem que muda + valor variável = conta de consumo numa série só
        RecurringSeries water = series("aquanorte", RecurringSeries.Flow.EXPENSE);
        assertThat(water.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(water.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        assertThat(water.getOccurrences()).isEqualTo(6);
        assertThat(water.getAnchorDay()).isEqualTo((short) 10);
        // MEDIANA das seis, e não média: numa conta de consumo o mês atípico
        // não pode reger a previsão dos outros
        assertThat(water.getExpectedAmount()).isEqualByComparingTo("90.4500");
        assertThat(water.getCategoryId()).isEqualTo(utilitiesCategory);

        // 2) adquirente instável + virada do mês = assinatura de valor fixo
        RecurringSeries streaming = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(streaming.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(streaming.getAmountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(streaming.getExpectedAmount()).isEqualByComparingTo("21.90");
        assertThat(streaming.getAnchorDay()).isEqualTo((short) 1);
        assertThat(streaming.getDayTolerance()).isEqualTo((short) 2);
        assertThat(streaming.getOccurrences()).isEqualTo(4);

        // 3) fatura ancorada pela palavra, mesmo citando o nome do titular
        RecurringSeries bill = series("fatura", RecurringSeries.Flow.EXPENSE);
        assertThat(bill.getOccurrences()).isEqualTo(4);
        assertThat(bill.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);

        // 4) troca de plano na mesma série, com a dica "Trimestral" mandando na cadência
        RecurringSeries phone = series("zetacel", RecurringSeries.Flow.EXPENSE);
        assertThat(phone.getOccurrences()).isEqualTo(5);
        assertThat(phone.getCadence()).isEqualTo(RecurringSeries.Cadence.QUARTERLY);

        // 5) movimentação do titular entre bancos = INTERNAL, nunca gasto/renda
        //    (contraparte de PIX é chaveada por primeiro nome + dominante)
        RecurringSeries internal = series("carlos pereira", RecurringSeries.Flow.INTERNAL);
        assertThat(internal.getOccurrences()).isEqualTo(6);
        assertThat(find("carlos pereira", RecurringSeries.Flow.EXPENSE)).isEmpty();
        assertThat(find("carlos pereira", RecurringSeries.Flow.INCOME)).isEmpty();

        // 6) renda: salário fixo e PIX informal do mesmo nome todo mês
        RecurringSeries salary = series("salario", RecurringSeries.Flow.INCOME);
        assertThat(salary.getAmountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(salary.getExpectedAmount()).isEqualByComparingTo("4500.00");
        assertThat(salary.getAnchorDay()).isEqualTo((short) 30);
        RecurringSeries informal = series("joana prado", RecurringSeries.Flow.INCOME);
        assertThat(informal.getOccurrences()).isEqualTo(3);

        // compras esparsas (1-2 ocorrências) não viram série
        assertThat(seriesStore).hasSize(7);
        assertThat(seriesStore).allMatch(RecurringSeries::isActive);
        assertThat(seriesStore).allMatch(s -> s.getSource() == RecurringSeries.Source.DETECTED);
    }

    @Test
    void secondRunOverSameDataChangesNothing() {
        loadCoreFixtures();
        service.detect(EMAIL);

        RecurrenceDetectionService.DetectionSummary second = service.detect(EMAIL);

        assertThat(second).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(seriesStore).hasSize(7);
        assertThat(linkStore).hasSize(31);
    }

    @Test
    void newTransactionJoinsExistingSeriesWithoutDuplicating() {
        loadCoreFixtures();
        service.detect(EMAIL);

        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 6, 2)));
        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(1);

        RecurringSeries streaming = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(streaming.getOccurrences()).isEqualTo(5);
        assertThat(streaming.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 2));
        assertThat(seriesStore).hasSize(7);
        assertThat(linkStore).hasSize(32);
    }

    @Test
    void seriesGoesInactiveAfterTwoSilentCyclesAndIsNeverDeleted() {
        // academia detectada numa varredura antiga parou de ser cobrada em março;
        // o salário segue entrando até junho e é a referência de "hoje"
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        RecurringSeries gymSeries = fitmaxSeries(gym);
        seriesStore.add(gymSeries);
        linkGymTransactions(gymSeries, gym);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(1); // salário
        assertThat(summary.seriesUpdated()).isEqualTo(1); // academia desativada
        assertThat(gymSeries.isActive()).isFalse();
        assertThat(seriesStore).contains(gymSeries); // desativa, nunca deleta

        // e a desativação não fica "piscando": nova varredura não muda nada
        RecurrenceDetectionService.DetectionSummary again = service.detect(EMAIL);
        assertThat(again).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(gymSeries.isActive()).isFalse();
    }

    @Test
    void userCuratedSeriesKeepsFieldsButAccumulatesEvidence() {
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        RecurringSeries curated = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("salario").flow(RecurringSeries.Flow.INCOME)
                .displayName("Salário CLT")
                .cadence(RecurringSeries.Cadence.WEEKLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("9999.00"))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .build();
        seriesStore.add(curated);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(3);
        // o que o usuário curou fica intacto...
        assertThat(curated.getDisplayName()).isEqualTo("Salário CLT");
        assertThat(curated.getCadence()).isEqualTo(RecurringSeries.Cadence.WEEKLY);
        assertThat(curated.getExpectedAmount()).isEqualByComparingTo("9999.00");
        // ...mas a evidência nova é registrada
        assertThat(curated.getOccurrences()).isEqualTo(3);
        assertThat(curated.getLastSeenAt()).isNotNull();
    }

    @Test
    void scheduledUserSeriesConciliatesFirstRealTransaction() {
        // agendamento manual (EC-096): chave "aluguel" derivada do displayName.
        // A 1ª transação real tem que conciliar mesmo abaixo do mínimo de 3 — e
        // mesmo que o token dominante do histórico fosse outro ("apartamento")
        RecurringSeries scheduled = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("aluguel").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Aluguel")
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .anchorDay((short) 5)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("1500.00"))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .startsAt(LocalDate.of(2025, 6, 1))
                .build();
        seriesStore.add(scheduled);
        transactions.add(tx("Pix enviado para Aluguel Apartamento 301", "DEBIT", "-1500.00",
                day(2025, 6, 5)));

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isZero();
        assertThat(summary.seriesUpdated()).isEqualTo(1);
        assertThat(summary.linksCreated()).isEqualTo(1);
        assertThat(linkStore).hasSize(1);
        assertThat(linkStore.get(0).getSeriesId()).isEqualTo(scheduled.getId());
        // evidência registrada...
        assertThat(scheduled.getOccurrences()).isEqualTo(1);
        assertThat(scheduled.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 5));
        // ...sem tocar na curadoria
        assertThat(scheduled.getDisplayName()).isEqualTo("Aluguel");
        assertThat(scheduled.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(scheduled.getExpectedAmount()).isEqualByComparingTo("1500.00");
        assertThat(scheduled.getAnchorDay()).isEqualTo((short) 5);
        // e nenhuma série paralela foi criada para a mesma cobrança
        assertThat(seriesStore).hasSize(1);

        // re-execução continua idempotente
        assertThat(service.detect(EMAIL))
                .isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
    }

    @Test
    void curatedKeyDoesNotHijackTransactionOutsideTheExpectedBand() {
        // agendamento "Conta de Luz" produz a chave genérica "luz": sem guarda,
        // qualquer PIX que cite a palavra entraria na série e marcaria o mês
        // como pago, com vínculo que nenhum endpoint desfaz
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        seriesStore.add(scheduled);
        BankTransaction bill = tx("Pagamento conta de luz Enerluz", "DEBIT", "-241.80", day(2025, 6, 12));
        BankTransaction unrelatedPix = tx("Pix enviado para Ana Luz", "DEBIT", "-60.00", day(2025, 6, 20));
        transactions.add(bill);
        transactions.add(unrelatedPix);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        // a conta de luz de verdade (valor na banda) concilia...
        assertThat(summary.linksCreated()).isEqualTo(1);
        assertThat(linkStore).hasSize(1);
        assertThat(linkStore.get(0).getBankTransactionId()).isEqualTo(bill.getId());
        assertThat(linkStore.get(0).getSeriesId()).isEqualTo(scheduled.getId());
        assertThat(scheduled.getOccurrences()).isEqualTo(1);
        assertThat(scheduled.getLastSeenAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 6, 12));
        // ...e o PIX para "Ana Luz" volta ao fluxo normal de descoberta, onde
        // uma ocorrência sozinha não vira série
        assertThat(seriesStore).hasSize(1);
        assertThat(find("ana", RecurringSeries.Flow.EXPENSE)).isEmpty();
    }

    @Test
    void curatedKeyLosesToTheTokenThatDominatesTheHistory() {
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        seriesStore.add(scheduled);
        // "supermercado" aparece em 3 meses, "luz" em um só: a compra de 235,00
        // cai na banda da conta de luz por puro acaso e mesmo assim é do mercado
        transactions.add(tx("Supermercado Bom Preco", "DEBIT", "-198.00", day(2025, 4, 8)));
        transactions.add(tx("Supermercado Bom Preco", "DEBIT", "-210.40", day(2025, 5, 8)));
        transactions.add(tx("Supermercado Luz da Manha", "DEBIT", "-235.00", day(2025, 6, 8)));

        service.detect(EMAIL);

        RecurringSeries market = series("supermercado", RecurringSeries.Flow.EXPENSE);
        assertThat(market.getOccurrences()).isEqualTo(3);
        assertThat(linkStore).hasSize(3);
        assertThat(linkStore).allMatch(link -> link.getSeriesId().equals(market.getId()));
        assertThat(scheduled.getOccurrences()).isZero();
        assertThat(scheduled.getLastSeenAt()).isNull();
    }

    @Test
    void alreadyConciliatedTransactionsSurviveAReadjustmentOutsideTheBand() {
        // assinatura agendada que reajustou de 21,90 para 29,90 (+36%): o
        // histórico já conciliado não pode ser expulso da própria série pela
        // banda do valor novo
        List<BankTransaction> paid = List.of(
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 4, 1)),
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 5, 1)));
        transactions.addAll(paid);
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-29.90", day(2025, 6, 1)));

        RecurringSeries curated = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("melodia").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Streaming Melodia")
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 1)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("29.90"))
                .occurrences(paid.size())
                .firstSeenAt(paid.get(0).getDate()).lastSeenAt(paid.get(1).getDate())
                .active(true).source(RecurringSeries.Source.USER)
                .build();
        seriesStore.add(curated);
        for (BankTransaction transaction : paid) {
            linkStore.add(RecurringSeriesLink.builder()
                    .id(UUID.randomUUID()).seriesId(curated.getId())
                    .bankTransactionId(transaction.getId()).matchedAt(OffsetDateTime.now()).build());
        }

        service.detect(EMAIL);

        assertThat(curated.getOccurrences()).isEqualTo(3);
        assertThat(linkStore).hasSize(3);
        assertThat(seriesStore).hasSize(1);
    }

    @Test
    void dismissedScheduledSeriesStopsCapturingByItsCuratedKey() {
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        scheduled.setActive(false);
        scheduled.setDismissed(true);
        seriesStore.add(scheduled);
        transactions.add(tx("Pagamento conta de luz Enerluz", "DEBIT", "-241.80", day(2025, 6, 12)));

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        // descartada, a chave "luz" perde a prioridade: a transação vai para o
        // token dominante ("enerluz") e, sozinha, não vira série nenhuma
        assertThat(summary).isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(linkStore).isEmpty();
        assertThat(scheduled.getOccurrences()).isZero();
        assertThat(scheduled.getLastSeenAt()).isNull();
        assertThat(scheduled.isDismissed()).isTrue();
    }

    @Test
    void unknownUserIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detectByUserId(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuário não encontrado");
    }

    @Test
    void retriesInFreshTransactionWhenConcurrentRunWinsTheUnique() {
        loadCoreFixtures();
        AtomicBoolean lostOnce = new AtomicBoolean(false);
        // doAnswer (e não when) para re-stubar: when() executaria o Answer do
        // setUp com argumento null durante o próprio stubbing
        doAnswer(inv -> {
            // 1ª tentativa: outra varredura (listener pós-import) commitou antes e
            // o flush desta violou o unique — o rollback desfez tudo, então o
            // callback nem chega a rodar nesta simulação
            if (lostOnce.compareAndSet(false, true)) {
                throw new DataIntegrityViolationException("uq_recurring_series_user_key_flow");
            }
            return inv.getArgument(0, TransactionCallback.class)
                    .doInTransaction(new SimpleTransactionStatus());
        }).when(transactionTemplate).execute(any());

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        assertThat(summary.seriesCreated()).isEqualTo(7);
        assertThat(summary.linksCreated()).isEqualTo(31);
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void degradesToEmptySummaryWhenBothAttemptsLoseTheRace() {
        doThrow(new DataIntegrityViolationException("uq_recurring_series_user_key_flow"))
                .when(transactionTemplate).execute(any());

        // corrida em cascata: os fatos já foram materializados pela execução
        // vencedora — o endpoint manual devolve "nada novo", nunca 500
        assertThat(service.detect(EMAIL))
                .isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    void pixPairAloneDoesNotSwallowLegitimateExpense() {
        // diarista recebe PIX mensal; no MESMO dia entra um PIX de cliente com o
        // MESMO valor — coincidência de dia+valor não pode virar INTERNAL
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 4, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 4, 5)));
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 5, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 5, 5)));
        transactions.add(tx("Pix enviado para Ana Lima", "DEBIT", "-400.00", day(2025, 6, 5)));
        transactions.add(tx("Pix recebido de Cliente Xis", "CREDIT", "400.00", day(2025, 6, 5)));

        service.detect(EMAIL);

        assertThat(series("ana lima", RecurringSeries.Flow.EXPENSE).getOccurrences()).isEqualTo(3);
        assertThat(find("ana lima", RecurringSeries.Flow.INTERNAL)).isEmpty();
        assertThat(series("cliente xis", RecurringSeries.Flow.INCOME).getOccurrences()).isEqualTo(3);
        assertThat(find("cliente xis", RecurringSeries.Flow.INTERNAL)).isEmpty();
    }

    @Test
    void sharedSurnameNeverFusesDifferentPixCounterparties() {
        // Cinco pessoas com o mesmo sobrenome, uma delas recebendo todo mês: no
        // extrato real "silva" juntava 26 pessoas numa série só. Só a Ana vira
        // série; os outros quatro são PIX avulsos e ficam de fora.
        transactions.add(tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 4, 5)));
        transactions.add(tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 5, 5)));
        transactions.add(tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 6, 5)));
        transactions.add(tx("Pix enviado: \"Cp :123-Bruno Costa\"", "DEBIT", "-40.00", day(2025, 3, 9)));
        transactions.add(tx("Pix enviado: \"Cp :456-Carla Mendes Costa\"", "DEBIT", "-25.00", day(2025, 5, 11)));
        transactions.add(tx("Pix enviado: \"Cp :789-Diego Costa Ramos\"", "DEBIT", "-60.00", day(2025, 5, 20)));
        transactions.add(tx("Pix enviado: \"Cp :789-null Elisa Costa\"", "DEBIT", "-12.00", day(2025, 6, 2)));

        service.detect(EMAIL);

        assertThat(seriesStore).hasSize(1);
        RecurringSeries ana = series("ana costa", RecurringSeries.Flow.EXPENSE);
        assertThat(ana.getOccurrences()).isEqualTo(3);
        assertThat(ana.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(find("costa", RecurringSeries.Flow.EXPENSE)).isEmpty();
    }

    @Test
    void samePixCounterpartyLandsInOneSeriesAcrossTheOwnersTwoBanks() {
        // A mesma pessoa paga ora pelo Inter ("Cp :NNN-Nome"), ora pelo Nubank,
        // que imprime o nome completo, o CPF mascarado e o BANCO DE DESTINO por
        // extenso ("MERCADO PAGO IP LTDA", "NU PAGAMENTOS - IP"). O resíduo do
        // banco não está na lista de nomes de banco e, como acompanha todo PIX
        // para aquele banco, é o token mais persistente do histórico: a chave
        // virava "ana mercado" num extrato e "ana beatriz" no outro
        transactions.add(tx("Pix enviado: \"Cp :260-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 1, 5)));
        transactions.add(tx("Pix enviado: \"Cp :260-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 2, 5)));
        transactions.add(tx("Transferência enviada pelo Pix - ANA BEATRIZ COSTA - •••.123.456-•• - "
                + "MERCADO PAGO IP LTDA (0323) Agência: 1 Conta: 12345678-9", "DEBIT", "-150.00", day(2025, 3, 5)));
        transactions.add(tx("Transferência enviada pelo Pix - ANA BEATRIZ COSTA - •••.123.456-•• - "
                + "MERCADO PAGO IP LTDA (0323) Agência: 1 Conta: 12345678-9", "DEBIT", "-150.00", day(2025, 4, 5)));
        // outros PIX do mesmo banco de destino, em meses diferentes, dão ao resíduo
        // do banco mais meses do que qualquer token do nome da Ana
        for (int month = 1; month <= 6; month++) {
            transactions.add(tx("Transferência enviada pelo Pix - BRUNO LIMA - •••.987.654-•• - "
                    + "MERCADO PAGO IP LTDA (0323) Agência: 1 Conta: 87654321-0", "DEBIT", "-30.00", day(2025, month, 20)));
            transactions.add(tx("Transferência recebida pelo Pix - CARLA MENDES - •••.111.222-•• - "
                    + "NU PAGAMENTOS - IP (0260) Agência: 1 Conta: 11112222-3", "CREDIT", "80.00", day(2025, month, 22)));
        }

        service.detect(EMAIL);

        // uma série só para a Ana, com os quatro PIX — qual token do nome completa
        // a chave é desempate do dominante (aqui "beatriz", por comprimento)
        List<RecurringSeries> anas = seriesStore.stream()
                .filter(s -> s.getMerchantKey().startsWith("ana ")).toList();
        assertThat(anas).hasSize(1);
        assertThat(anas.get(0).getOccurrences()).isEqualTo(4);
        assertThat(seriesStore).noneMatch(s -> s.getMerchantKey().contains("mercado")
                || s.getMerchantKey().contains("pago")
                || s.getMerchantKey().contains("pagamentos"));
        assertThat(series("bruno lima", RecurringSeries.Flow.EXPENSE).getOccurrences()).isEqualTo(6);
        assertThat(series("carla mendes", RecurringSeries.Flow.INCOME).getOccurrences()).isEqualTo(6);
    }

    @Test
    void abbreviatedMiddleNameStillLandsInTheSamePixSeries() {
        // o banco de origem ora imprime o nome completo, ora abrevia o meio
        transactions.add(tx("Pix recebido: \"Cp :123-Helena Maria Duarte Reis\"", "CREDIT", "300.00", day(2025, 4, 10)));
        transactions.add(tx("Pix recebido: \"Cp :123-Helena M D Reis\"", "CREDIT", "300.00", day(2025, 5, 10)));
        transactions.add(tx("Pix recebido: \"Cp :123-Helena Maria Duarte Reis\"", "CREDIT", "300.00", day(2025, 6, 10)));

        service.detect(EMAIL);

        assertThat(seriesStore).hasSize(1);
        assertThat(series("helena reis", RecurringSeries.Flow.INCOME).getOccurrences()).isEqualTo(3);
    }

    @Test
    void bankNameDoesNotSwallowInvestmentsAndCashbackIntoOneSeries() {
        // "inter" acompanhava CDB, cashback e estorno e era o token mais persistente:
        // 271 lançamentos de quatro entidades caíam numa série INCOME só
        for (int month = 1; month <= 6; month++) {
            transactions.add(tx("Resgate: \"CDB COFRINHO BANCO INTER SA\"", "CREDIT", "80.00", day(2025, month, 3)));
            transactions.add(tx("Resgate: \"CDB COFRINHO BANCO INTER SA\"", "CREDIT", "45.00", day(2025, month, 17)));
            transactions.add(tx("Cashback: \"INTER PRE 20GB MENSAL\"", "CREDIT", "2.40", day(2025, month, 9)));
        }

        service.detect(EMAIL);

        assertThat(seriesStore).hasSize(2);
        assertThat(find("inter", RecurringSeries.Flow.INCOME)).isEmpty();
        // resgate/cdb/cofrinho empatam em meses: vence o token mais longo
        RecurringSeries redemptions = series("cofrinho", RecurringSeries.Flow.INCOME);
        assertThat(redemptions.getOccurrences()).isEqualTo(12);
        RecurringSeries cashback = series("cashback", RecurringSeries.Flow.INCOME);
        assertThat(cashback.getOccurrences()).isEqualTo(6);
        assertThat(cashback.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
    }

    @Test
    void loneCadenceHintDoesNotOverrideTheGroupsIntervals() {
        // seis cobranças mensais sem dica e UMA com "Trimestral" por último: a
        // dica da mais recente só manda quando a maioria do grupo também a traz
        for (int month = 1; month <= 6; month++) {
            transactions.add(tx("Clube do Livro Aurora", "DEBIT", "-39.90", day(2025, month, 12)));
        }
        transactions.add(tx("Clube do Livro Aurora Trimestral", "DEBIT", "-39.90", day(2025, 7, 12)));

        service.detect(EMAIL);

        RecurringSeries club = series("aurora", RecurringSeries.Flow.EXPENSE);
        assertThat(club.getOccurrences()).isEqualTo(7);
        assertThat(club.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
    }

    @Test
    void cadenceFollowsTheRecentIntervalsNotTheWholeHistory() {
        // fatura paga em 3-4 parcelas por mês no primeiro semestre e em uma só
        // nos 13 meses seguintes: a mediana de todos os intervalos dava IRREGULAR
        // e a maior despesa recorrente ficava fora da previsão
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int month = 0; month < 6; month++) {
            LocalDate base = start.plusMonths(month);
            transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-200.00", at(base.withDayOfMonth(3))));
            transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-150.00", at(base.withDayOfMonth(11))));
            transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-120.00", at(base.withDayOfMonth(19))));
            transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-90.00", at(base.withDayOfMonth(26))));
        }
        for (int month = 6; month < 19; month++) {
            transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-1300.00",
                    at(start.plusMonths(month).withDayOfMonth(8))));
        }

        service.detect(EMAIL);

        RecurringSeries bill = series("fatura", RecurringSeries.Flow.EXPENSE);
        assertThat(bill.getOccurrences()).isEqualTo(37);
        assertThat(bill.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(bill.getAnchorDay()).isEqualTo((short) 8);
        assertThat(bill.getDayTolerance()).isEqualTo((short) 0);
        // o valor esperado também é o de hoje: a média da vida inteira (com as
        // parcelas de 90 a 200) previa um terço do que a fatura custa
        assertThat(bill.getAmountType()).isEqualTo(RecurringSeries.AmountType.FIXED);
        assertThat(bill.getExpectedAmount()).isEqualByComparingTo("1300.00");
        assertThat(bill.isActive()).isTrue();
    }

    @Test
    void cardBillPaymentLegsFuseIntoOneInternalSeries() {
        // As DUAS pernas do pagamento de fatura, marcadas na importação
        // (EC-106): a saída da conta corrente e o crédito que entra no cartão.
        // Antes, a âncora "fatura" impedia INTERNAL e nasciam duas séries
        // mensais — "fatura|EXPENSE" e "fatura|INCOME" — e a previsão de saldo
        // projetava uma receita do tamanho da fatura que não existe.
        for (int month = 4; month <= 6; month++) {
            transactions.add(txInternal("PAGAMENTO FATURA CARTAO", "DEBIT", "-500.00", day(2025, month, 12)));
            transactions.add(txInternal("Pagamento de fatura", "CREDIT", "500.00", day(2025, month, 12)));
        }

        service.detect(EMAIL);

        assertThat(find("fatura", RecurringSeries.Flow.EXPENSE)).isEmpty();
        assertThat(find("fatura", RecurringSeries.Flow.INCOME)).isEmpty();
        // os dois sentidos caem na MESMA série, pela fusão que já existia
        assertThat(series("fatura", RecurringSeries.Flow.INTERNAL).getOccurrences()).isEqualTo(6);
    }

    @Test
    void unmarkedFaturaStillBecomesExpenseSeries() {
        // sem a marca da importação (upload manual de OFX, usuário sem cartão
        // conectado) nada muda: o pagamento da fatura segue sendo a única
        // representação do gasto do cartão e continua despesa
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-500.00", day(2025, 4, 12)));
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-480.00", day(2025, 5, 12)));
        transactions.add(tx("Pagamento fatura cartao", "DEBIT", "-520.00", day(2025, 6, 12)));

        service.detect(EMAIL);

        assertThat(series("fatura", RecurringSeries.Flow.EXPENSE).getOccurrences()).isEqualTo(3);
        assertThat(find("fatura", RecurringSeries.Flow.INTERNAL)).isEmpty();
    }

    @Test
    void faturamentoDoesNotFallIntoFaturaAnchor() {
        // "faturamento" contém "fatura" como substring, mas é outra entidade:
        // a âncora agora casa por token exato
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 4, 12)));
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 5, 12)));
        transactions.add(tx("Recebimento faturamento mensal", "CREDIT", "1200.00", day(2025, 6, 12)));

        service.detect(EMAIL);

        assertThat(find("fatura", RecurringSeries.Flow.INCOME)).isEmpty();
        RecurringSeries billing = series("faturamento", RecurringSeries.Flow.INCOME);
        assertThat(billing.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(billing.getOccurrences()).isEqualTo(3);
    }

    @Test
    void fixedDetectionUsesTrueMedianOfAmountsNotChronologicalMiddle() {
        // meio CRONOLÓGICO = 1000 → banda de 50 engoliria a variação de 48 e
        // classificaria FIXED; a mediana real (955) dá banda de 47.75 e expõe.
        // A mesma mediana é o valor previsto: 955, e não a média 969, porque um
        // mês de R$ 1.000 no meio de dois de R$ 95x não muda o que o plano custa
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-952.00", day(2025, 4, 20)));
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-1000.00", day(2025, 5, 20)));
        transactions.add(tx("Plano Saude Vitalis", "DEBIT", "-955.00", day(2025, 6, 20)));

        service.detect(EMAIL);

        RecurringSeries plan = series("vitalis", RecurringSeries.Flow.EXPENSE);
        assertThat(plan.getAmountType()).isEqualTo(RecurringSeries.AmountType.VARIABLE);
        assertThat(plan.getExpectedAmount()).isEqualByComparingTo("955.0000");
    }

    @Test
    void monthlyCadenceSurvivesMissedMonths() {
        // um mês falhado: gaps [31, 59] — a mediana superior dava IRREGULAR
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 1, 10)));
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 2, 10)));
        transactions.add(tx("Academia Corpo Livre", "DEBIT", "-99.90", day(2025, 4, 10)));
        // dois meses falhados: gaps [30, 90] — a mediana superior dava QUARTERLY
        // falso; no empate MONTHLY×QUARTERLY vence o ciclo mais curto
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 1, 10)));
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 2, 9)));
        transactions.add(tx("Clube Recreio Bom", "DEBIT", "-80.00", day(2025, 5, 10)));

        service.detect(EMAIL);

        RecurringSeries gym = series("academia", RecurringSeries.Flow.EXPENSE);
        assertThat(gym.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(gym.getAnchorDay()).isEqualTo((short) 10);

        RecurringSeries club = series("recreio", RecurringSeries.Flow.EXPENSE);
        assertThat(club.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
    }

    @Test
    void dismissedSeriesNeverResurrectsOnNewEvidence() {
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        // cobrança NOVA chega DEPOIS de o usuário ter descartado a série
        transactions.add(tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 4, 10)));

        RecurringSeries dismissed = fitmaxSeries(gym);
        dismissed.setActive(false);
        dismissed.setDismissed(true);
        seriesStore.add(dismissed);
        linkGymTransactions(dismissed, gym);

        service.detect(EMAIL);

        // a evidência é registrada (vínculo + contagem), mas o descarte fica de pé
        assertThat(dismissed.isActive()).isFalse();
        assertThat(dismissed.isDismissed()).isTrue();
        assertThat(dismissed.getOccurrences()).isEqualTo(4);
        assertThat(linkStore).hasSize(4);
    }

    @Test
    void machineDeactivatedSeriesStillReactivatesOnNewEvidence() {
        List<BankTransaction> gym = List.of(
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 1, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 2, 10)),
                tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 3, 10)));
        transactions.addAll(gym);
        transactions.add(tx("Dm*fitmax Sao Paulo Bra", "DEBIT", "-99.90", day(2025, 4, 10)));

        // desativada por staleness (dismissed=false): cobrança nova reativa
        RecurringSeries paused = fitmaxSeries(gym);
        paused.setActive(false);
        seriesStore.add(paused);
        linkGymTransactions(paused, gym);

        service.detect(EMAIL);

        assertThat(paused.isActive()).isTrue();
        assertThat(paused.isDismissed()).isFalse();
    }

    @Test
    void seriesWhoseKeyChangedHandsItsLinksToTheTwinAndGoesInactive() {
        // Produção já tem séries gravadas com as chaves ANTIGAS (deploy anterior
        // ao EC-111): o cashback do plano de celular vivia em "inter|INCOME". Com a
        // derivação nova a mesma transação cai em "cashback|INCOME". Sem tratamento
        // a série velha ficava ativa (não está stale: a última ocorrência é de
        // hoje) e a gêmea nascia SEM vínculo nenhum — as transações continuavam
        // presas à velha — e a previsão de saldo projetava as duas.
        List<BankTransaction> cashback = new ArrayList<>();
        for (int month = 1; month <= 6; month++) {
            cashback.add(tx("Cashback: \"INTER PRE 20GB MENSAL\"", "CREDIT", "2.40", day(2025, month, 9)));
        }
        transactions.addAll(cashback);
        RecurringSeries legacy = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("inter").flow(RecurringSeries.Flow.INCOME)
                .displayName("Cashback: \"INTER PRE 20GB MENSAL\"")
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 9).dayTolerance((short) 0)
                .amountType(RecurringSeries.AmountType.FIXED).expectedAmount(new BigDecimal("2.4000"))
                .occurrences(cashback.size())
                .firstSeenAt(cashback.get(0).getDate()).lastSeenAt(cashback.get(5).getDate())
                .active(true).source(RecurringSeries.Source.DETECTED)
                .build();
        seriesStore.add(legacy);
        linkGymTransactions(legacy, cashback);

        RecurrenceDetectionService.DetectionSummary summary = service.detect(EMAIL);

        RecurringSeries twin = series("cashback", RecurringSeries.Flow.INCOME);
        assertThat(summary.seriesCreated()).isEqualTo(1);
        // os seis vínculos MUDAM de dona: a gêmea passa a ter o histórico
        assertThat(summary.linksCreated()).isEqualTo(6);
        assertThat(linkStore).hasSize(6);
        assertThat(linkStore).allMatch(link -> link.getSeriesId().equals(twin.getId()));
        assertThat(twin.getOccurrences()).isEqualTo(6);
        assertThat(twin.isActive()).isTrue();
        // a órfã não é apagada, mas sai da tela e da previsão
        assertThat(legacy.isActive()).isFalse();
        assertThat(legacy.isDismissed()).isFalse();
        assertThat(seriesStore).contains(legacy);
        assertThat(summary.seriesUpdated()).isEqualTo(1);

        // e a varredura seguinte não fica oscilando
        assertThat(service.detect(EMAIL))
                .isEqualTo(new RecurrenceDetectionService.DetectionSummary(0, 0, 0));
        assertThat(legacy.isActive()).isFalse();
        assertThat(linkStore).hasSize(6);
    }

    @Test
    void orphanWhoseTransactionsSplitBelowTheMinimumStillReleasesAllOfThem() {
        // "costa" juntava três pessoas; só a Ana tem histórico para virar série.
        // Os vínculos do Bruno e da Carla também são liberados: presos à órfã,
        // bloqueariam a série de cada um quando ela vier a existir.
        List<BankTransaction> costa = List.of(
                tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 4, 5)),
                tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 5, 5)),
                tx("Pix enviado: \"Cp :123-Ana Beatriz Costa\"", "DEBIT", "-150.00", day(2025, 6, 5)),
                tx("Pix enviado: \"Cp :123-Bruno Costa\"", "DEBIT", "-40.00", day(2025, 3, 9)),
                tx("Pix enviado: \"Cp :456-Carla Mendes Costa\"", "DEBIT", "-25.00", day(2025, 5, 11)));
        transactions.addAll(costa);
        RecurringSeries legacy = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("costa").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Pix enviado: \"Cp :123-Ana Beatriz Costa\"")
                .cadence(RecurringSeries.Cadence.IRREGULAR)
                .amountType(RecurringSeries.AmountType.VARIABLE).expectedAmount(new BigDecimal("103.0000"))
                .occurrences(costa.size())
                .firstSeenAt(costa.get(3).getDate()).lastSeenAt(costa.get(2).getDate())
                .active(true).source(RecurringSeries.Source.DETECTED)
                .build();
        seriesStore.add(legacy);
        linkGymTransactions(legacy, costa);

        service.detect(EMAIL);

        RecurringSeries ana = series("ana costa", RecurringSeries.Flow.EXPENSE);
        assertThat(linkStore).hasSize(3);
        assertThat(linkStore).allMatch(link -> link.getSeriesId().equals(ana.getId()));
        // IRREGULAR nunca fica stale: sem esta regra a órfã ficaria ativa para sempre
        assertThat(legacy.isActive()).isFalse();
    }

    @Test
    void dismissedOrphanReleasesItsLinksButKeepsTheDismissal() {
        List<BankTransaction> cashback = new ArrayList<>();
        for (int month = 1; month <= 3; month++) {
            cashback.add(tx("Cashback: \"INTER PRE 20GB MENSAL\"", "CREDIT", "2.40", day(2025, month, 9)));
        }
        transactions.addAll(cashback);
        RecurringSeries dismissed = RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("inter").flow(RecurringSeries.Flow.INCOME)
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 9).dayTolerance((short) 0)
                .amountType(RecurringSeries.AmountType.FIXED).expectedAmount(new BigDecimal("2.4000"))
                .occurrences(3)
                .firstSeenAt(cashback.get(0).getDate()).lastSeenAt(cashback.get(2).getDate())
                .active(false).dismissed(true).source(RecurringSeries.Source.DETECTED)
                .build();
        seriesStore.add(dismissed);
        linkGymTransactions(dismissed, cashback);

        service.detect(EMAIL);

        RecurringSeries twin = series("cashback", RecurringSeries.Flow.INCOME);
        assertThat(linkStore).hasSize(3);
        assertThat(linkStore).allMatch(link -> link.getSeriesId().equals(twin.getId()));
        assertThat(dismissed.isActive()).isFalse();
        assertThat(dismissed.isDismissed()).isTrue();
    }

    @Test
    void userSeriesNotRefoundKeepsItsLinksAndStaysActive() {
        // agendamento manual cuja transação conciliada não está mais na base
        // (extrato removido): curadoria do usuário nunca é órfã para o motor
        RecurringSeries scheduled = scheduledUtilities("luz",
                RecurringSeries.AmountType.VARIABLE, "230.00");
        seriesStore.add(scheduled);
        linkStore.add(RecurringSeriesLink.builder()
                .id(UUID.randomUUID()).seriesId(scheduled.getId())
                .bankTransactionId(UUID.randomUUID()).matchedAt(OffsetDateTime.now()).build());
        transactions.add(tx("Padaria Estrela do Sul", "DEBIT", "-12.50", day(2025, 5, 21)));

        service.detect(EMAIL);

        assertThat(linkStore).hasSize(1);
        assertThat(linkStore.get(0).getSeriesId()).isEqualTo(scheduled.getId());
        assertThat(scheduled.isActive()).isTrue();
    }

    @Test
    void transactionAliasNeverFeedsTheEntityKeyNorTheSeriesName() {
        // as três cobranças foram renomeadas pelo usuário (EC-094); a série tem
        // que continuar nascendo do descritivo do banco, senão o apelido de uma
        // transação fatiaria a série ou criaria outra entidade do nada
        List<BankTransaction> streaming = List.of(
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 1, 31)),
                tx("Ebn*melodia Curitiba Bra", "DEBIT", "-21.90", day(2025, 3, 2)),
                tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 4, 1)));
        streaming.forEach(transaction -> transaction.setDisplayAlias("Streaming da Ana"));
        transactions.addAll(streaming);

        service.detect(EMAIL);

        assertThat(seriesStore).hasSize(1);
        RecurringSeries series = series("melodia", RecurringSeries.Flow.EXPENSE);
        assertThat(series.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        assertThat(series.getOccurrences()).isEqualTo(3);
        // nome da série sai da última descrição do banco, não do apelido
        assertThat(series.getDisplayName()).isEqualTo("Dm*melodia Sao Paulo Bra");
        assertThat(seriesStore).noneMatch(s -> s.getMerchantKey().contains("streaming")
                || s.getMerchantKey().contains("ana"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void loadCoreFixtures() {
        // conta de água fictícia: rótulo muda no meio do histórico, valor varia
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-85.30", day(2025, 1, 10)));
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-92.10", day(2025, 2, 10)));
        transactions.add(txCat("Pagamento AQUANORTE | AQUANORTE", "DEBIT", "-78.55", day(2025, 3, 11)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-101.20", day(2025, 4, 10)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-88.00", day(2025, 5, 9)));
        transactions.add(txCat("Pagamento de Convênio | AQUANORTE", "DEBIT", "-90.45", day(2025, 6, 10)));

        // streaming fictício: adquirente e cidade mudam, cobrança vira o mês (30-03)
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 1, 31)));
        transactions.add(tx("Ebn*melodia Curitiba Bra", "DEBIT", "-21.90", day(2025, 3, 2)));
        transactions.add(tx("No Estabelecimento Dm *melodia Stockholm Bra", "DEBIT", "-21.90", day(2025, 4, 1)));
        transactions.add(tx("Dm*melodia Sao Paulo Bra", "DEBIT", "-21.90", day(2025, 5, 3)));

        // fatura de cartão fictícia: dois rótulos sem nada em comum além da palavra
        transactions.add(tx("Pagamento efetuado | Fatura cartão Aurora", "DEBIT", "-1450.10", day(2025, 3, 5)));
        transactions.add(tx("Pagamento efetuado | Pagamento Fatura - CARLOS PEREIRA", "DEBIT", "-1320.55", day(2025, 4, 4)));
        transactions.add(tx("Pagamento efetuado | Fatura cartão Aurora", "DEBIT", "-1510.00", day(2025, 5, 6)));
        transactions.add(tx("Pagamento efetuado | Pagamento Fatura - CARLOS PEREIRA", "DEBIT", "-1275.40", day(2025, 6, 5)));

        // plano de celular fictício: upgrade de plano e mudança para trimestral
        transactions.add(tx("Zetacel Pre 10gb Mensal", "DEBIT", "-19.99", day(2025, 1, 8)));
        transactions.add(tx("Zetacel Pre 10gb Mensal", "DEBIT", "-19.99", day(2025, 2, 7)));
        transactions.add(tx("Zetacel Pre 15gb Mensal", "DEBIT", "-24.99", day(2025, 3, 8)));
        transactions.add(tx("Zetacel Pre 15gb Mensal", "DEBIT", "-24.99", day(2025, 4, 8)));
        transactions.add(tx("Zetacel Cel Trimestral 20GB", "DEBIT", "-69.90", day(2025, 6, 9)));

        // titular movendo dinheiro entre os próprios bancos (par PIX no mesmo dia)
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-500.00", day(2025, 4, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "500.00", day(2025, 4, 1)));
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-650.00", day(2025, 5, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "650.00", day(2025, 5, 1)));
        transactions.add(tx("Pix enviado para Carlos Pereira", "DEBIT", "-600.00", day(2025, 6, 1)));
        transactions.add(tx("Pix recebido de Carlos Pereira", "CREDIT", "600.00", day(2025, 6, 1)));

        // salário via portabilidade (rótulo genérico, sem empregador)
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 4, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 5, 30)));
        transactions.add(tx("Salário recebido - Portabilidade", "CREDIT", "4500.00", day(2025, 6, 30)));

        // renda informal: PIX do mesmo nome fictício todo mês
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "800.00", day(2025, 4, 15)));
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "750.00", day(2025, 5, 15)));
        transactions.add(tx("Pix recebido de Joana Prado", "CREDIT", "820.00", day(2025, 6, 16)));

        // ruído: compras esparsas que NÃO podem virar série
        transactions.add(tx("Ifd*Cantina Da Nona Sao Paulo Bra", "DEBIT", "-54.30", day(2025, 5, 20)));
        transactions.add(tx("Padaria Estrela do Sul", "DEBIT", "-12.50", day(2025, 5, 21)));
        transactions.add(tx("Padaria Estrela do Sul", "DEBIT", "-15.00", day(2025, 6, 21)));
    }

    /** Gasto fixo agendado pelo usuário (EC-096), como o POST o teria criado. */
    private RecurringSeries scheduledUtilities(String merchantKey, RecurringSeries.AmountType amountType,
                                               String expectedAmount) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey(merchantKey).flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Conta de Luz")
                .cadence(RecurringSeries.Cadence.MONTHLY).anchorDay((short) 12)
                .amountType(amountType)
                .expectedAmount(new BigDecimal(expectedAmount))
                .occurrences(0)
                .active(true).source(RecurringSeries.Source.USER)
                .startsAt(LocalDate.of(2025, 6, 1))
                .build();
    }

    /** Série da academia fictícia como uma varredura anterior a teria gravado. */
    private RecurringSeries fitmaxSeries(List<BankTransaction> gym) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey("fitmax").flow(RecurringSeries.Flow.EXPENSE)
                .displayName("Dm*fitmax Sao Paulo Bra")
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .anchorDay((short) 10).dayTolerance((short) 0)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("99.9000"))
                .occurrences(gym.size())
                .firstSeenAt(gym.get(0).getDate()).lastSeenAt(gym.get(gym.size() - 1).getDate())
                .active(true).source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private void linkGymTransactions(RecurringSeries series, List<BankTransaction> gym) {
        for (BankTransaction tx : gym) {
            linkStore.add(RecurringSeriesLink.builder()
                    .id(UUID.randomUUID()).seriesId(series.getId())
                    .bankTransactionId(tx.getId()).matchedAt(OffsetDateTime.now()).build());
        }
    }


    @Test
    void invoiceThatGrowsIsForecastByTheRecentMedianNotTheLifetimeAverage() {
        // Os 12 pagamentos de fatura do dono, do extrato real (12/2025 a 09/2026).
        // Ela subiu de R$ 568 para R$ 2.311 sem um degrau, e a média da janela
        // longa previa R$ 1.480 — mil reais abaixo do que ele paga hoje
        String[][] faturas = {
                {"2025-12-07", "-568.74"}, {"2026-01-05", "-1412.62"},
                {"2026-02-04", "-1391.70"}, {"2026-03-04", "-1669.00"},
                {"2026-04-02", "-2013.90"}, {"2026-05-06", "-2114.42"},
                {"2026-06-03", "-2625.72"}, {"2026-07-04", "-1291.42"},
                {"2026-08-05", "-2383.17"}, {"2026-09-05", "-2311.49"},
        };
        for (String[] fatura : faturas) {
            LocalDate dia = LocalDate.parse(fatura[0]);
            transactions.add(tx("Pagamento efetuado: Pagamento fatura cartao Inter", "DEBIT",
                    fatura[1], day(dia.getYear(), dia.getMonthValue(), dia.getDayOfMonth())));
        }

        service.detect(EMAIL);

        RecurringSeries fatura = series("fatura", RecurringSeries.Flow.EXPENSE);
        assertThat(fatura.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        // Mediana das seis últimas — com contagem par, o elemento de cima, que é
        // o mesmo critério que a banda do FIXED já usava. Aqui ele cai em
        // R$ 2.311,49, que é exatamente a fatura seguinte no extrato real.
        // Para comparar: a média da janela longa previa R$ 1.480 e a das seis
        // últimas R$ 2.123, as duas puxadas para baixo pelo mês em que ele pagou
        // metade da fatura
        assertThat(fatura.getExpectedAmount()).isEqualByComparingTo("2311.4900");
    }

    @Test
    void monthlyDepositSurvivesAnExtraTopUpInTheMiddleOfTheMonth() {
        // Vale-refeição do dono: cai todo mês, e uma recarga extra de R$ 24 em
        // 07/07 partiu os intervalos em 12/22/30 dias. A mediana caiu em 22 e a
        // segunda maior renda dele saiu IRREGULAR — fora da previsão
        transactions.add(tx("Deposito transferido", "CREDIT", "770.00", day(2026, 6, 25)));
        transactions.add(tx("Deposito transferido", "CREDIT", "24.00", day(2026, 7, 7)));
        transactions.add(tx("Deposito transferido", "CREDIT", "735.00", day(2026, 7, 29)));
        transactions.add(tx("Deposito transferido", "CREDIT", "735.00", day(2026, 8, 28)));

        service.detect(EMAIL);

        RecurringSeries vale = series("transferido", RecurringSeries.Flow.INCOME);
        // Três meses de calendário seguidos: o que acontece em todo mês é mensal,
        // independentemente de quantos dias separam duas linhas
        assertThat(vale.getCadence()).isEqualTo(RecurringSeries.Cadence.MONTHLY);
        // E a mediana ignora a recarga de R$ 24, que a média (R$ 566) engolia
        assertThat(vale.getExpectedAmount()).isEqualByComparingTo("735.0000");
    }

    @Test
    void threeVisitsAMonthDoNotBecomeAMonthlyBill() {
        // O teto de uma ocorrência e meia por mês existe para isto: a padaria de
        // toda semana aparece em meses seguidos e não é conta mensal
        for (int mes = 6; mes <= 8; mes++) {
            transactions.add(tx("Padaria do Ze", "DEBIT", "-12.00", day(2026, mes, 3)));
            transactions.add(tx("Padaria do Ze", "DEBIT", "-15.00", day(2026, mes, 12)));
            transactions.add(tx("Padaria do Ze", "DEBIT", "-9.00", day(2026, mes, 25)));
        }

        service.detect(EMAIL);

        RecurringSeries padaria = series("padaria", RecurringSeries.Flow.EXPENSE);
        assertThat(padaria.getCadence()).isNotEqualTo(RecurringSeries.Cadence.MONTHLY);
    }

    private RecurringSeries series(String merchantKey, RecurringSeries.Flow flow) {
        return find(merchantKey, flow).orElseThrow(
                () -> new AssertionError("Série não encontrada: " + merchantKey + "/" + flow));
    }

    private Optional<RecurringSeries> find(String merchantKey, RecurringSeries.Flow flow) {
        return seriesStore.stream()
                .filter(s -> s.getMerchantKey().equals(merchantKey) && s.getFlow() == flow)
                .findFirst();
    }

    private BankTransaction tx(String description, String type, String amount, OffsetDateTime date) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type(type)
                .amount(new BigDecimal(amount))
                .description(description)
                .date(date)
                .build();
    }

    /** Perna de movimentação entre contas do titular, marcada na importação. */
    private BankTransaction txInternal(String description, String type, String amount, OffsetDateTime date) {
        BankTransaction transaction = tx(description, type, amount, date);
        transaction.setInternalTransfer(true);
        return transaction;
    }

    private BankTransaction txCat(String description, String type, String amount, OffsetDateTime date) {
        BankTransaction transaction = tx(description, type, amount, date);
        transaction.setCategoryId(utilitiesCategory);
        return transaction;
    }

    private OffsetDateTime day(int year, int month, int dayOfMonth) {
        return at(LocalDate.of(year, month, dayOfMonth));
    }

    private OffsetDateTime at(LocalDate date) {
        return OffsetDateTime.of(date, LocalTime.NOON, ZoneOffset.UTC);
    }
}
