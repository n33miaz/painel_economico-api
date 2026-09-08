package br.com.economize.service;

import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A faxina que faz as correções de 07/09/2026 sobreviverem ao próximo arquivo.
 *
 * <p>O que este teste protege não é cada varredura — elas têm testes próprios —
 * e sim que as quatro rodem, e <b>nesta ordem</b>.
 */
@ExtendWith(MockitoExtension.class)
class StatementHygieneServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private InternalTransferService internalTransferService;

    @Mock
    private FamilyTransferService familyTransferService;

    @Mock
    private DuplicateTransactionService duplicateService;

    @Mock
    private RecurrenceDetectionService recurrenceDetectionService;

    @Mock
    private UserRepository userRepository;

    private StatementHygieneService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        service = new StatementHygieneService(internalTransferService, familyTransferService,
                duplicateService, recurrenceDetectionService, userRepository);
        lenient().when(internalTransferService.reconcileByOwnName(EMAIL))
                .thenReturn(new InternalTransferService.Outcome(1755, 197, true));
        lenient().when(familyTransferService.reconcile(EMAIL))
                .thenReturn(new FamilyTransferService.Outcome(1755, 68, 1));
        lenient().when(duplicateService.sweep(EMAIL, false))
                .thenReturn(new DuplicateTransactionService.Outcome(
                        1755, 20, new BigDecimal("4855.09"), false, List.of()));
        lenient().when(recurrenceDetectionService.detect(EMAIL))
                .thenReturn(new RecurrenceDetectionService.DetectionSummary(3, 12, 40));
    }

    @Test
    @DisplayName("as quatro varreduras rodam, e a recorrência é a última")
    void shouldRunEveryPassWithDetectionLast() {
        StatementHygieneService.Outcome resultado = service.runFor(EMAIL);

        InOrder ordem = inOrder(internalTransferService, familyTransferService,
                duplicateService, recurrenceDetectionService);
        ordem.verify(internalTransferService).reconcileByOwnName(EMAIL);
        ordem.verify(familyTransferService).reconcile(EMAIL);
        ordem.verify(duplicateService).sweep(EMAIL, false);
        // Por último de propósito: rodando antes das marcas, um Pix do dono para
        // ele mesmo vira "despesa mensal" E "receita mensal" na previsão
        ordem.verify(recurrenceDetectionService).detect(EMAIL);

        assertThat(resultado.internalMarked()).isEqualTo(197);
        assertThat(resultado.familyMarked()).isEqualTo(68);
        assertThat(resultado.duplicatesMarked()).isEqualTo(20);
        assertThat(resultado.seriesCreated()).isEqualTo(3);
        assertThat(resultado.seriesUpdated()).isEqualTo(12);
    }

    @Test
    @DisplayName("a varredura de duplicatas MARCA — não é ensaio")
    void shouldSweepForRealAndNotDryRun() {
        service.runFor(EMAIL);

        // dryRun=true aqui deixaria o relatório bonito e o número errado
        verify(duplicateService).sweep(EMAIL, false);
        verify(duplicateService, never()).sweep(EMAIL, true);
    }

    @Test
    @DisplayName("pelo id do usuário, resolve o e-mail antes de varrer")
    void shouldResolveTheUserWhenCalledById() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.runFor(user.getId());

        verify(internalTransferService).reconcileByOwnName(EMAIL);
    }

    @Test
    @DisplayName("usuário que não existe não vira faxina silenciosa")
    void shouldFailLoudlyForAnUnknownUser() {
        UUID desconhecido = UUID.randomUUID();
        when(userRepository.findById(desconhecido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runFor(desconhecido))
                .hasMessageContaining("não encontrado");
        verify(internalTransferService, never()).reconcileByOwnName(any());
    }
}
