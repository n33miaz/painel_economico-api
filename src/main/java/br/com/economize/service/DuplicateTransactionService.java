package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A mesma transação que entrou por duas portas.
 *
 * <p>Medido no extrato real do dono em 07/09/2026: <b>18 lançamentos em dobro,
 * R$ 4.855 de um lado</b>. A assinatura é sempre a mesma e é o que dá confiança
 * ao pareamento:
 *
 * <ul>
 *   <li><b>Valor idêntico</b>, com sinal — não "parecido".</li>
 *   <li><b>Até um dia de diferença</b>: as duas fontes carimbam a data de forma
 *       diferente (o banco usa a data de lançamento, o arquivo a de efetivação).</li>
 *   <li><b>Um lado tem conta de origem e o outro não.</b> Este é o discriminante
 *       que separa duplicata de coincidência: quem tem {@code accountId} veio da
 *       conexão bancária, quem não tem veio de arquivo importado. Dois Pix de
 *       R$ 125 no mesmo dia para pessoas diferentes vêm os DOIS da mesma fonte,
 *       e por isso não casam aqui.</li>
 * </ul>
 *
 * <p><b>Qual lado sobrevive:</b> o que tem conta de origem. Ele carrega a
 * instituição, alimenta a tela de Cartões e o compartilhamento por conta na
 * Casa; a linha do arquivo é a mesma informação sem essa dimensão.
 *
 * <p><b>Marca, não apaga</b> — ver o comentário da V26. Apagar é irreversível, o
 * critério é heurístico, e reimportar o arquivo não desfaz (o upload é
 * idempotente por hash). O pedido do dono veio junto com "mas não pode faltar
 * nada", e marcar entrega o número certo sem perder a linha.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateTransactionService {

    /**
     * Janela do pareamento. Um dia, e não dois: com dois, um salário creditado
     * na segunda e um Pix igual na quarta viram "duplicata".
     */
    private static final Duration JANELA = Duration.ofDays(1);

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /** Marca (ou desmarca) UMA linha à mão. Decisão do usuário: a varredura não a desfaz. */
    public BankTransaction setIgnored(String email, UUID transactionId, boolean ignored) {
        User user = requireUser(email);
        BankTransaction tx = bankTransactionRepository
                .findByIdAndUserId(transactionId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Transação não encontrada"));
        tx.setIgnored(ignored);
        tx.setIgnoredReason(ignored ? BankTransaction.IgnoredReason.USER : null);
        return bankTransactionRepository.save(tx);
    }

    /**
     * Encontra os pares e marca o lado do arquivo.
     *
     * @param dryRun true = só relata, não grava. É o padrão de uso: o dono olha
     *               a lista antes de qualquer marca, porque o critério é
     *               heurístico e a conta é dele.
     */
    public Outcome sweep(String email, boolean dryRun) {
        User user = requireUser(email);
        List<BankTransaction> all = bankTransactionRepository
                .findAllByUserIdOrderByDateDesc(user.getId());

        // Agrupa por valor exato: só quem tem o mesmo valor pode ser par, e isso
        // reduz a comparação de N² para a soma dos quadrados de grupos pequenos
        Map<BigDecimal, List<BankTransaction>> porValor = new LinkedHashMap<>();
        for (BankTransaction tx : all) {
            if (tx.isIgnored()) continue;
            porValor.computeIfAbsent(tx.getAmount(), k -> new ArrayList<>()).add(tx);
        }

        List<Pair> pares = new ArrayList<>();
        List<UUID> marcar = new ArrayList<>();
        for (List<BankTransaction> mesmoValor : porValor.values()) {
            if (mesmoValor.size() < 2) continue;
            List<BankTransaction> comConta = mesmoValor.stream()
                    .filter(t -> t.getAccountId() != null).toList();
            List<BankTransaction> semConta = new ArrayList<>(mesmoValor.stream()
                    .filter(t -> t.getAccountId() == null).toList());

            for (BankTransaction conector : comConta) {
                BankTransaction par = null;
                for (BankTransaction arquivo : semConta) {
                    Duration diff = Duration.between(conector.getDate(), arquivo.getDate()).abs();
                    if (diff.compareTo(JANELA) <= 0) {
                        par = arquivo;
                        break;
                    }
                }
                // um lado só pode ser pareado uma vez: sem isto, três linhas de
                // R$ 12 no mesmo dia gerariam pares cruzados e marcariam demais
                if (par == null) continue;
                semConta.remove(par);
                pares.add(new Pair(conector.getId(), par.getId(), conector.getAmount(),
                        conector.getDate().toLocalDate().toString(),
                        par.getDate().toLocalDate().toString(),
                        conector.getDescription(), par.getDescription()));
                marcar.add(par.getId());
            }
        }

        if (!dryRun && !marcar.isEmpty()) {
            bankTransactionRepository.markAsIgnoredDuplicate(user.getId(), marcar);
        }
        BigDecimal volume = pares.stream()
                .map(p -> p.amount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Varredura de duplicatas: {} par(es), volume {}, dryRun={}, user={}",
                pares.size(), volume, dryRun, email);
        return new Outcome(all.size(), pares.size(), volume, dryRun, pares);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param kept      id do lado que fica (o que tem conta de origem)
     * @param ignoredId id do lado marcado (o que veio de arquivo)
     */
    public record Pair(UUID kept, UUID ignoredId, BigDecimal amount,
                       String keptDate, String ignoredDate,
                       String keptDescription, String ignoredDescription) {
    }

    /**
     * @param scanned quantos lançamentos foram examinados
     * @param pairs   quantos pares foram encontrados
     * @param volume  soma dos valores em módulo de UM lado dos pares
     * @param dryRun  true = nada foi gravado, é só o relatório
     */
    public record Outcome(int scanned, int pairs, BigDecimal volume, boolean dryRun,
                          List<Pair> details) {
    }
}
