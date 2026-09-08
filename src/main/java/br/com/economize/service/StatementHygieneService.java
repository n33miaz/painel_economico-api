package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * A faxina que roda depois de cada importação — e por que ela existe.
 *
 * <p>Em 07/09/2026 três correções foram aplicadas à mão no extrato do dono: as
 * transferências entre contas dele mesmo, as transferências entre ele e a
 * esposa e as 20 linhas que tinham entrado por duas fontes. A Casa saiu de
 * R$ 8.225,91 de receita em agosto para R$ 6.017,17, que é a renda real do
 * casal.
 *
 * <p><b>Nenhuma dessas correções sobreviveria ao próximo arquivo importado.</b>
 * Elas foram chamadas uma vez, por endpoint; a importação seguinte traria
 * linhas novas sem marca nenhuma e os mesmos erros voltariam — a mesma
 * transferência contada duas vezes, a mesma linha em duplicidade. Este serviço
 * transforma aquelas três chamadas numa etapa fixa do caminho de entrada de
 * dados.
 *
 * <p><b>A ordem importa</b>, e é esta:
 * <ol>
 *   <li>movimentação própria — marca o que é dinheiro do titular trocando de
 *       bolso;</li>
 *   <li>transferência entre pessoas da casa — o que sobrou e circulou entre o
 *       casal;</li>
 *   <li>duplicatas — o que entrou por duas portas. Depois das duas marcas
 *       acima, porque a varredura pula o que já está marcado como ignorado e
 *       não faz sentido parear linhas que já saíram das somas;</li>
 *   <li>recorrência — <b>por último</b>, para o detector enxergar as marcas.
 *       Rodando antes, um Pix para si mesmo vira "despesa mensal" e
 *       "receita mensal" ao mesmo tempo, e a previsão de saldo projeta as
 *       duas.</li>
 * </ol>
 *
 * <p>Tudo aqui é idempotente: rodar de novo não desmarca nada e não pareia o
 * que já foi pareado. E tudo é reversível pelo app — a marca some com um toque.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementHygieneService {

    private final InternalTransferService internalTransferService;
    private final FamilyTransferService familyTransferService;
    private final DuplicateTransactionService duplicateService;
    private final RecurrenceDetectionService recurrenceDetectionService;
    private final UserRepository userRepository;

    public Outcome runFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        return runFor(user.getEmail());
    }

    /**
     * Roda as quatro etapas em sequência. Cada uma é independente da anterior no
     * sentido de que uma falha não desfaz o que já foi feito — mas nenhuma é
     * pulada silenciosamente: o que quebrar sobe, porque uma faxina que falha
     * sem avisar deixa o número errado na tela do dono parecendo certo.
     */
    public Outcome runFor(String email) {
        InternalTransferService.Outcome proprias = internalTransferService.reconcileByOwnName(email);
        FamilyTransferService.Outcome casa = familyTransferService.reconcile(email);
        DuplicateTransactionService.Outcome duplicatas = duplicateService.sweep(email, false);
        RecurrenceDetectionService.DetectionSummary recorrencia =
                recurrenceDetectionService.detect(email);

        Outcome resultado = new Outcome(
                proprias.marked(), casa.marked(), duplicatas.pairs(),
                recorrencia.seriesCreated(), recorrencia.seriesUpdated());
        log.info("Faxina pós-importação: {} própria(s), {} da casa, {} duplicata(s), "
                        + "{} série(s) nova(s), {} atualizada(s), user={}",
                resultado.internalMarked(), resultado.familyMarked(), resultado.duplicatesMarked(),
                resultado.seriesCreated(), resultado.seriesUpdated(), email);
        return resultado;
    }

    /**
     * @param internalMarked   linhas que passaram a contar como dinheiro do próprio titular
     * @param familyMarked     linhas que saíram da soma da casa
     * @param duplicatesMarked pares que entraram por duas fontes
     * @param seriesCreated    séries de recorrência novas
     * @param seriesUpdated    séries que mudaram de valor, cadência ou dia
     */
    public record Outcome(int internalMarked, int familyMarked, int duplicatesMarked,
                          int seriesCreated, int seriesUpdated) {
    }
}
