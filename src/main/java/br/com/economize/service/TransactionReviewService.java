package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.statement.ReviewApplyRequest;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O elo do aprendizado: toda decisão do usuário (aprovar sugestão ou corrigir
 * categoria) promove a transação a CONFIRMED e grava/reforça uma regra EXACT com
 * a descrição normalizada — a próxima importação do mesmo estabelecimento resolve
 * sozinha, com confiança maior que a das keywords.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionReviewService {

    /** Mesma confiança da importação: sugestão de modelo não vale o de uma regra. */
    private static final BigDecimal CONF_AI = new BigDecimal("0.50");

    private static final EnumSet<BankTransaction.ReviewStatus> PENDING =
            EnumSet.of(BankTransaction.ReviewStatus.SUGGESTED, BankTransaction.ReviewStatus.UNCATEGORIZED);

    private final BankTransactionRepository bankTransactionRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final UserRepository userRepository;
    // O mesmo motor da importação: recategorizar não pode ter regra própria,
    // senão a fila passaria a discordar do que o próximo arquivo vai produzir
    private final CategorizationEngine categorizationEngine;
    // ObjectProvider como na importação: desde o EC-107 o bean existe sempre,
    // e quem decide se há IA para esta conta é o próprio suggester
    private final ObjectProvider<AiCategorySuggester> aiSuggester;

    /**
     * Extrato filtrado. A janela (EC-092) pode ser um mês do calendário ou o
     * ciclo ancorado no dia do salário — a listagem não distingue os dois, e é
     * isso que permite ao app abrir "as transações deste período" a partir da
     * mesma janela que pediu à análise. Janela nula devolve o histórico inteiro.
     */
    public List<BankTransaction> listTransactions(String email, AnalysisWindow window,
                                                  BankTransaction.ReviewStatus status, UUID categoryId) {
        return listTransactions(email, window, status, categoryId, null);
    }

    /**
     * Mesma listagem, podendo recortar por ORIGEM (EC-113) — é ela que responde
     * "o que eu gastei NO CARTÃO neste mês" sem que o app precise somar nada.
     * {@code accountId} nulo devolve tudo, inclusive os lançamentos sem origem
     * (histórico e upload manual de arquivo).
     *
     * <p>O recorte por origem é feito em MEMÓRIA, como os de status e categoria,
     * e não desce para a consulta: a janela já é lida inteira de qualquer forma,
     * o volume mensal de finanças pessoais é pequeno, e "sem filtro" aqui
     * significa devolver também os lançamentos de origem nula — o que uma
     * consulta derivada por accountId não expressa sem um segundo caminho. Por
     * isso o índice parcial da V16 declara um leitor só: a fatura.
     */
    public List<BankTransaction> listTransactions(String email, AnalysisWindow window,
                                                  BankTransaction.ReviewStatus status, UUID categoryId,
                                                  UUID accountId) {
        User user = requireUser(email);
        List<BankTransaction> base;
        if (window != null) {
            base = bankTransactionRepository
                    .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                            user.getId(), window.startInstant(), window.endExclusiveInstant());
        } else {
            base = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
        }
        // filtros residuais em memória: volume mensal de finanças pessoais é pequeno
        return base.stream()
                .filter(t -> status == null || t.getReviewStatus() == status)
                .filter(t -> categoryId == null || categoryId.equals(t.getCategoryId()))
                .filter(t -> accountId == null || accountId.equals(t.getAccountId()))
                .toList();
    }

    public List<BankTransaction> reviewQueue(String email, UUID uploadId) {
        User user = requireUser(email);
        if (uploadId != null) {
            return bankTransactionRepository
                    .findAllByUserIdAndUploadIdOrderByDateDesc(user.getId(), uploadId).stream()
                    .filter(t -> PENDING.contains(t.getReviewStatus()))
                    .toList();
        }
        return bankTransactionRepository.findAllByUserIdAndReviewStatusInOrderByDateDesc(user.getId(), PENDING);
    }

    public long pendingCount(String email) {
        User user = requireUser(email);
        return bankTransactionRepository.countByUserIdAndReviewStatusIn(user.getId(), PENDING);
    }

    /**
     * Roda o motor de novo sobre o que AINDA espera revisão.
     *
     * <p>A categorização acontecia só no momento da importação: melhorar o
     * vocabulário (ou ganhar uma regra aprendida numa correção) passava a valer
     * para as importações SEGUINTES e nunca alcançava o que já estava no banco.
     * Com o extrato de dois anos do dono, isso significava centenas de linhas
     * paradas na fila esperando decisão manual para estabelecimentos que o
     * motor já sabia resolver — o iFood pelo prefixo da maquininha (IFD*) é o
     * caso exemplar.
     *
     * <p>Só mexe em quem está PENDENTE: transação CONFIRMED é decisão do
     * usuário e não pode ser sobrescrita por palpite de máquina. Linha que
     * continua sem resposta fica como está, e o `internalTransfer` da
     * importação é preservado — só quem importou sabia disso.
     */
    public RecategorizeOutcome recategorizePending(String email) {
        User user = requireUser(email);
        List<BankTransaction> pending = bankTransactionRepository
                .findAllByUserIdAndReviewStatusInOrderByDateDesc(user.getId(), PENDING);
        if (pending.isEmpty()) return new RecategorizeOutcome(0, 0, 0, 0);

        CategorizationEngine.Context ctx = categorizationEngine.contextFor(user.getId());
        List<BankTransaction> changed = new java.util.ArrayList<>();
        int resolved = 0;

        for (BankTransaction tx : pending) {
            CategorizationEngine.Result result = categorizationEngine.categorize(
                    ctx, tx.getDescription(), tx.getType(), tx.isInternalTransfer());
            if (!result.resolved()) continue;

            UUID before = tx.getCategoryId();
            // Sugerir de novo a MESMA categoria não é mudança: contar isso
            // inflaria o número que a tela mostra ao usuário
            if (before != null && before.equals(result.category().getId())) continue;

            tx.setCategoryId(result.category().getId());
            tx.setCategory(legacyKey(result.category()));
            tx.setCategorizedBy(result.by());
            tx.setConfidence(result.confidence());
            tx.setNormalizedDescription(result.normalizedDescription());
            // SUGGESTED, nunca CONFIRMED: o motor sugere, o usuário confirma —
            // é o que mantém a fila de revisão sendo revisão
            tx.setReviewStatus(BankTransaction.ReviewStatus.SUGGESTED);
            changed.add(tx);
            if (before == null) resolved++;
        }

        // Segundo passe: o que o vocabulário não reconheceu vai para a IA, quando
        // esta conta tem alguma ligada. A ordem importa e é barata do jeito certo
        // — regra aprendida e keyword resolvem de graça e no mesmo processo; a
        // chamada ao modelo sai UMA vez, com as descrições que sobraram.
        int byAi = applyAiToUnresolved(user, pending, changed);
        resolved += byAi;

        if (!changed.isEmpty()) bankTransactionRepository.saveAll(changed);
        int stillPending = pending.size() - resolved;
        log.info("Recategorização: {} pendentes revistas, {} ganharam sugestão ({} pela IA), "
                        + "{} seguem sem, user={}",
                pending.size(), resolved, byAi, stillPending, email);
        return new RecategorizeOutcome(pending.size(), resolved, stillPending, byAi);
    }

    /**
     * Pergunta ao modelo o que o vocabulário não soube responder.
     *
     * <p>Só entra quem continua <b>sem categoria</b> depois do passe de regras, e
     * a lista vai por descrição normalizada DISTINTA: 253 transações do dono são
     * 137 nomes de estabelecimento, e é isso que viaja. Nada acontece se a conta
     * não tem IA — a pergunta {@code appliesTo} vem ANTES de carregar o catálogo,
     * pelo mesmo motivo da importação: montar o catálogo custa uma consulta, e
     * pagá-la para descobrir que não havia IA seria cobrar de todo mundo o preço
     * de um bônus que quase ninguém ligou.
     *
     * <p>Devolve {@code SUGGESTED} e {@code categorizedBy=AI}, que é o que faz o
     * chip da revisão dizer "sugerida por IA". O modelo erra com confiança; quem
     * confirma continua sendo a pessoa.
     */
    private int applyAiToUnresolved(User user, List<BankTransaction> pending,
                                    List<BankTransaction> changed) {
        AiCategorySuggester suggester = aiSuggester.getIfAvailable();
        if (suggester == null) return 0;

        List<BankTransaction> unresolved = pending.stream()
                .filter(tx -> tx.getCategoryId() == null)
                .filter(tx -> tx.getNormalizedDescription() != null
                        && !tx.getNormalizedDescription().isBlank())
                .toList();
        if (unresolved.isEmpty() || !suggester.appliesTo(user)) return 0;

        List<Category> catalog = categoryRepository.findVisibleTo(user.getId()).stream()
                .filter(c -> !c.isArchived())
                .toList();
        List<String> keys = unresolved.stream()
                .map(BankTransaction::getNormalizedDescription)
                .distinct()
                .toList();
        Map<String, String> suggestions = suggester.suggest(user, keys, catalog);
        if (suggestions.isEmpty()) return 0;

        Map<String, Category> bySlug = catalog.stream()
                .collect(Collectors.toMap(Category::getSlug, Function.identity(), (a, b) -> a));
        int applied = 0;
        for (BankTransaction tx : unresolved) {
            Category category = bySlug.get(suggestions.get(tx.getNormalizedDescription()));
            if (category == null) continue;
            tx.setCategoryId(category.getId());
            tx.setCategory(legacyKey(category));
            tx.setCategorizedBy(BankTransaction.CategorizedBy.AI);
            tx.setConfidence(CONF_AI);
            tx.setReviewStatus(BankTransaction.ReviewStatus.SUGGESTED);
            changed.add(tx);
            applied++;
        }
        return applied;
    }

    /**
     * Aplica decisões em lote. Cada item confirma um grupo de transações numa
     * categoria; learnPattern (default true) grava o padrão para o futuro.
     */
    public ReviewOutcome apply(String email, ReviewApplyRequest request) {
        User user = requireUser(email);
        int confirmed = 0;
        int rulesSaved = 0;

        for (ReviewApplyRequest.Item item : request.items()) {
            Category category = categoryRepository.findAccessible(item.categoryId(), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada"));
            List<BankTransaction> txs =
                    bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), item.transactionIds());
            if (txs.isEmpty()) continue;

            Map<String, Category> toLearn = new HashMap<>();
            for (BankTransaction tx : txs) {
                assign(tx, category, BankTransaction.CategorizedBy.USER);
                if (!Boolean.FALSE.equals(item.learnPattern()) && learnable(tx)) {
                    toLearn.put(tx.getNormalizedDescription(), category);
                }
            }
            bankTransactionRepository.saveAll(txs);
            confirmed += txs.size();
            for (Map.Entry<String, Category> entry : toLearn.entrySet()) {
                upsertLearnedRule(user, entry.getKey(), entry.getValue());
                rulesSaved++;
            }
        }
        log.info("Revisão aplicada: {} transações confirmadas, {} padrões salvos, user={}", confirmed, rulesSaved, email);
        return new ReviewOutcome(confirmed, rulesSaved);
    }

    /**
     * Aprova de uma vez tudo que o motor sugeriu (não toca as sem categoria).
     * A aprovação também cristaliza os padrões — é o caminho rápido do usuário
     * que confere a lista e concorda com tudo.
     */
    public ReviewOutcome confirmAll(String email, UUID uploadId) {
        User user = requireUser(email);
        List<BankTransaction> pending = reviewQueue(email, uploadId).stream()
                .filter(t -> t.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED
                        && t.getCategoryId() != null)
                .toList();
        if (pending.isEmpty()) return new ReviewOutcome(0, 0);

        Map<UUID, Category> categories = new HashMap<>();
        Map<String, Category> toLearn = new HashMap<>();
        for (BankTransaction tx : pending) {
            Category category = categories.computeIfAbsent(tx.getCategoryId(),
                    id -> categoryRepository.findAccessible(id, user.getId()).orElse(null));
            if (category == null) continue;
            tx.setReviewStatus(BankTransaction.ReviewStatus.CONFIRMED);
            if (learnable(tx)) {
                toLearn.put(tx.getNormalizedDescription(), category);
            }
        }
        bankTransactionRepository.saveAll(pending);
        int rulesSaved = 0;
        for (Map.Entry<String, Category> entry : toLearn.entrySet()) {
            upsertLearnedRule(user, entry.getKey(), entry.getValue());
            rulesSaved++;
        }
        log.info("Confirmação em lote: {} transações, {} padrões, user={}", pending.size(), rulesSaved, email);
        return new ReviewOutcome(pending.size(), rulesSaved);
    }

    /**
     * A decisão do usuário sobre ESTA linha pode virar regra para as próximas?
     *
     * <p>Precisa de descrição normalizada — é ela a chave do padrão — e a linha
     * NÃO pode ser perna de movimentação entre contas do titular (EC-106). O
     * motivo é o ciclo de contaminação: o descritivo de um pagamento de fatura
     * ou de um estorno ("PAGAMENTO EFETUADO", "ESTORNO DE COMPRA") é genérico e
     * se repete em linhas que NÃO são perna interna. Aprender a categoria que o
     * usuário escolheu para a perna carimbaria todas elas, e a regra aprendida
     * roda antes de qualquer keyword. A perna interna já é resolvida por fato
     * estrutural no motor, então não há nada a aprender aqui: a categoria dela
     * não depende do texto.
     */
    private boolean learnable(BankTransaction tx) {
        return !tx.isInternalTransfer()
                && tx.getNormalizedDescription() != null
                && !tx.getNormalizedDescription().isBlank();
    }

    private void assign(BankTransaction tx, Category category, BankTransaction.CategorizedBy by) {
        tx.setCategoryId(category.getId());
        tx.setCategory(legacyKey(category));
        tx.setReviewStatus(BankTransaction.ReviewStatus.CONFIRMED);
        tx.setCategorizedBy(by);
        tx.setConfidence(null);
    }

    private void upsertLearnedRule(User user, String pattern, Category category) {
        CategoryRule rule = categoryRuleRepository.findByUserIdAndPattern(user.getId(), pattern)
                .orElse(null);
        if (rule == null) {
            // EXACT de propósito: o padrão é a chave normalizada inteira, então não
            // há risco de uma regra aprendida "vazar" para estabelecimentos parecidos
            categoryRuleRepository.save(CategoryRule.builder()
                    .user(user)
                    .category(category)
                    .pattern(pattern)
                    .matchType(CategoryRule.MatchType.EXACT)
                    .origin(CategoryRule.Origin.LEARNED)
                    .hits(1)
                    .lastHitAt(OffsetDateTime.now())
                    .build());
            return;
        }
        rule.setCategory(category);
        rule.setHits(rule.getHits() + 1);
        rule.setLastHitAt(OffsetDateTime.now());
        categoryRuleRepository.save(rule);
    }

    /**
     * Mantém a coluna legada em sincronia: relatórios ainda agregam pela string.
     */
    static String legacyKey(Category category) {
        String key = category.getSystemKey() != null ? category.getSystemKey() : category.getSlug();
        return key.length() > 32 ? key.substring(0, 32) : key;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    public record ReviewOutcome(int confirmed, int rulesSaved) {
    }

    /**
     * @param reviewed      quantas linhas pendentes o motor reexaminou
     * @param resolved      quantas ganharam uma sugestão que não tinham
     * @param stillPending  quantas seguem sem resposta (decisão do usuário)
     */
    public record RecategorizeOutcome(int reviewed, int resolved, int stillPending,
                                      int resolvedByAi) {
    }
}
