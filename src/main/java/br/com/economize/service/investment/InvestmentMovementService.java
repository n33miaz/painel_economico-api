package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O lado "extrato" dos investimentos: os lançamentos de aplicação, resgate e
 * rendimento que já estão em {@code bank_transactions}, lidos como MOVIMENTOS
 * de investimento.
 *
 * <p>É DERIVAÇÃO na leitura, não materialização. O extrato do dono tem ~340
 * linhas de "Aplicação CDB", "Resgate" e "Rendimentos" do Mercado Pago; o
 * motor de regras já as categoriza como Investimentos (V8), e copiá-las para
 * outra tabela criaria duas verdades sobre o mesmo lançamento — a categoria
 * que o usuário corrige na revisão teria que ser propagada, e a dedupe do
 * pipeline não alcançaria a cópia. Ler do extrato, toda vez, é barato (volume
 * mensal de finanças pessoais) e nunca diverge.
 *
 * <p>Dois sinais independentes elegem um lançamento: a CATEGORIA (a linha está
 * em Investimentos, ou numa subcategoria dela, ou numa categoria do usuário
 * com "invest" no slug) OU o TEXTO normalizado casando o vocabulário de
 * investimento com fronteira de palavra. O texto entra porque a categoria pode
 * estar errada ou vazia (linha ainda em revisão) e o usuário não pode deixar
 * de ver a aplicação de ontem por isso; a fronteira de palavra entra porque
 * "aplic" solto acha "aplicativo" — que é exatamente o que "COMPRA APLICATIVO
 * UBER" não é.
 */
@Service
public class InvestmentMovementService {

    /** Teto da janela: dez anos de calendário. Acima disso é pedido absurdo, não caso de uso. */
    static final int MAX_MONTHS = 120;

    static final String KIND_APPLY = "APPLY";
    static final String KIND_REDEEM = "REDEEM";
    static final String KIND_YIELD = "YIELD";
    static final String KIND_OTHER = "OTHER";

    // Vocabulário sobre a descrição JÁ normalizada (minúscula, sem acento, sem
    // jargão de banco — ver DescriptionNormalizer): daí "acoes" e "poupan" sem
    // acento. Prefixos levam \w* para pegar "aplicacao"/"aplicacoes" e
    // "rendimento"/"rendimentos"; "aplic" exclui "aplicativo" explicitamente.
    static final Pattern INVESTMENT_TERMS = Pattern.compile(
            "\\b(?:aplic(?!ativo)\\w*|resgat\\w*|rendiment\\w*|cdb|rdb|lci|lca|tesouro|poupan\\w*|fundos?|etfs?"
                    + "|acoes|dividend\\w*|jcp|juros sobre capital|renda fixa)\\b");
    private static final Pattern YIELD_TERMS = Pattern.compile(
            "\\b(?:rendiment\\w*|juros|dividend\\w*|jcp|provento\\w*|remunera\\w*)\\b");
    private static final Pattern REDEEM_TERMS = Pattern.compile(
            "\\b(?:resgat\\w*|venda|liquidac\\w*)\\b");
    private static final Pattern APPLY_TERMS = Pattern.compile(
            "\\b(?:aplic(?!ativo)\\w*|compra|invest\\w*)\\b");

    private final BankTransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final ConnectorAccountRepository accountRepository;
    private final UserRepository userRepository;
    private final int defaultMonths;

    public InvestmentMovementService(BankTransactionRepository transactionRepository,
                                     CategoryRepository categoryRepository,
                                     ConnectorAccountRepository accountRepository,
                                     UserRepository userRepository,
                                     @Value("${economize.investments.movement-months-default:12}") int defaultMonths) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.defaultMonths = defaultMonths;
    }

    public InvestmentResponses.Movements movements(String email, Integer months) {
        return movements(requireUser(email), months);
    }

    /**
     * A janela é de meses de CALENDÁRIO, o atual incluído: {@code months=12} vai
     * do 1º dia de onze meses atrás até hoje. Contar dias corridos faria a
     * aplicação do dia 1º entrar e sair da janela conforme o dia da consulta.
     */
    public InvestmentResponses.Movements movements(User user, Integer months) {
        int window = months != null ? months : defaultMonths;
        if (window < 1 || window > MAX_MONTHS) {
            throw new IllegalArgumentException(String.format(
                    "Janela inválida: months deve estar entre 1 e %d (recebido: %d)", MAX_MONTHS, window));
        }
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.withDayOfMonth(1).minusMonths(window - 1L);
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Set<UUID> investmentCategories = investmentCategoryIds(user.getId());
        Map<UUID, ConnectorAccount> accounts = accountRepository
                .findAllByUserIdOrderByNameAsc(user.getId()).stream()
                .collect(Collectors.toMap(ConnectorAccount::getId, Function.identity(), (a, b) -> a));

        List<InvestmentResponses.MovementRow> rows = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal redeemed = BigDecimal.ZERO;
        BigDecimal yield = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;

        for (BankTransaction tx : transactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(user.getId(), start, end)) {
            // perna de pagamento de fatura não é investimento, seja qual for o texto
            if (tx.isInternalTransfer()) continue;
            String normalized = DescriptionNormalizer.normalize(tx.getDescription());
            if (!isInvestment(tx, normalized, investmentCategories)) continue;

            String kind = classify(tx, normalized);
            BigDecimal magnitude = tx.getAmount().abs();
            switch (kind) {
                case KIND_APPLY -> applied = applied.add(magnitude);
                case KIND_REDEEM -> redeemed = redeemed.add(magnitude);
                case KIND_YIELD -> yield = yield.add(magnitude);
                default -> other = other.add(magnitude);
            }
            ConnectorAccount account = tx.getAccountId() != null ? accounts.get(tx.getAccountId()) : null;
            rows.add(new InvestmentResponses.MovementRow(
                    tx.getId(),
                    tx.getDate().toLocalDate(),
                    kind,
                    tx.getAmount(),
                    tx.displayDescription(),
                    institutionOf(account),
                    tx.getAccountId()));
        }

        return new InvestmentResponses.Movements(
                window, from, to, rows,
                new InvestmentResponses.MovementTotals(applied, redeemed, yield, other),
                applied.subtract(redeemed));
    }

    /**
     * Só as descrições normalizadas dos movimentos da janela — o que o perfil
     * precisa para derivar interesses sem carregar o resto.
     */
    public List<String> normalizedDescriptions(User user, int months) {
        return movements(user, months).movements().stream()
                .map(row -> DescriptionNormalizer.normalize(row.description()))
                .toList();
    }

    private boolean isInvestment(BankTransaction tx, String normalized, Set<UUID> investmentCategories) {
        if ("INVESTMENT".equals(tx.getCategory())) return true;
        if (tx.getCategoryId() != null && investmentCategories.contains(tx.getCategoryId())) return true;
        return INVESTMENT_TERMS.matcher(normalized).find();
    }

    /**
     * O tipo do movimento pelo SINAL e pelo texto, nesta ordem de prioridade.
     *
     * <p>Crédito: rendimento/juros/dividendo/JCP é YIELD; texto de aplicação num
     * crédito é estorno de aplicação (OTHER); qualquer outro crédito vindo de
     * investimento é dinheiro voltando — REDEEM, mesmo sem a palavra "resgate"
     * ("CDB Mercado Pago" positivo é resgate).
     *
     * <p>Débito: texto de resgate ou de rendimento num débito é ajuste ("IR
     * sobre rendimentos", "estorno de resgate") — OTHER; qualquer outro débito
     * é dinheiro indo para o investimento — APPLY ("CDB Mercado Pago" negativo
     * é aplicação, e a categoria já disse que é investimento).
     */
    static String classify(BankTransaction tx, String normalized) {
        boolean credit = tx.getAmount().signum() != 0
                ? tx.getAmount().signum() > 0
                : "CREDIT".equalsIgnoreCase(tx.getType());
        boolean yieldText = YIELD_TERMS.matcher(normalized).find();
        boolean redeemText = REDEEM_TERMS.matcher(normalized).find();
        boolean applyText = APPLY_TERMS.matcher(normalized).find();

        if (credit) {
            if (yieldText) return KIND_YIELD;
            if (applyText && !redeemText) return KIND_OTHER;
            return KIND_REDEEM;
        }
        if (redeemText || yieldText) return KIND_OTHER;
        return KIND_APPLY;
    }

    /**
     * Ids das categorias que significam "investimento" para este usuário: o
     * seed do sistema (system_key INVESTMENT), qualquer subcategoria dele e
     * categoria própria com "invest" no slug ("investimentos-exterior").
     */
    private Set<UUID> investmentCategoryIds(UUID userId) {
        Set<UUID> ids = new HashSet<>();
        for (Category category : categoryRepository.findVisibleTo(userId)) {
            if (isInvestmentCategory(category)
                    || (category.getParent() != null && isInvestmentCategory(category.getParent()))) {
                ids.add(category.getId());
            }
        }
        return ids;
    }

    private static boolean isInvestmentCategory(Category category) {
        if ("INVESTMENT".equals(category.getSystemKey())) return true;
        String slug = category.getSlug();
        return slug != null && slug.toLowerCase(Locale.ROOT).contains("invest");
    }

    private static String institutionOf(ConnectorAccount account) {
        if (account == null) return null;
        return account.getInstitution() != null ? account.getInstitution() : account.getName();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
