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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * As fontes de renda e a jornada de trabalho (EC-135 e EC-141).
 *
 * <p>Duas ideias sustentam esta classe:
 *
 * <ol>
 * <li><b>Cada fonte tem o próprio calendário.</b> Salário dia 5, VR dia 25 — e o
 * gasto herda o ciclo de quem o pagou. É o que resolve "gastei com o VR antes de
 * o salário cair" sem inventar exceção no fechamento do mês.</li>
 * <li><b>Sugerir não é declarar.</b> O motor de recorrência acha o salário no
 * extrato, mas quem confirma quanto ganha é o dono do salário. Sugestão entra
 * como sugestão e só vira fonte com o aceite — porque o valor da hora de vida da
 * pessoa é calculado em cima disso.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class IncomeSourceService {

    // Os palpites são comparados contra o texto JÁ normalizado (sem acento, sem
    // pontuação, cercado de espaços). Por isso "vr" aparece como " vr ": solto,
    // ele casaria dentro de "livro". Palavra longa dispensa a cerca.
    private static final List<String> MEAL_HINTS = List.of(
            " vr ", "vale refeicao", "refeicao", "alelo", "sodexo", "ticket",
            "caju", "pluxee");

    private static final List<String> FOOD_HINTS = List.of(
            " va ", "vale alimentacao", "alimentacao");

    private static final List<String> SALARY_HINTS = List.of(
            "salario", "folha", "pagamento", "remuneracao", "provento", "vencimento", "pgto");

    private static final List<String> ADVANCE_HINTS = List.of(
            "adiantamento", "antecipacao", " vale ", " 13o ", "decimo terceiro");

    private static final BigDecimal WEEKS_PER_MONTH =
            BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);

    private final IncomeSourceRepository incomeSourceRepository;
    private final WorkProfileRepository workProfileRepository;
    private final RecurringSeriesRepository recurringSeriesRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public WishResponses.IncomeOverview overview(String email) {
        User user = requireUser(email);

        List<WishResponses.IncomeSourceItem> sources =
                incomeSourceRepository.findAllByUserIdOrderByKindAscNameAsc(user.getId()).stream()
                        .map(WishResponses.IncomeSourceItem::from)
                        .toList();

        WorkProfile profile = workProfileRepository.findById(user.getId()).orElse(null);
        WishResponses.WorkProfileItem profileItem =
                WishResponses.WorkProfileItem.from(profile, hoursPerMonth(profile));

        return new WishResponses.IncomeOverview(sources, profileItem, suggestions(user.getId()));
    }

    /**
     * Séries de renda que o extrato já provou e que ainda não viraram fonte.
     *
     * <p>Séries IRREGULAR ficam de fora: renda sem cadência estimável não tem
     * âncora, e âncora é justamente o que a fonte existe para guardar.
     */
    private List<WishResponses.Suggestion> suggestions(UUID userId) {
        List<WishResponses.Suggestion> out = new ArrayList<>();
        // Uma consulta para o conjunto inteiro, e não uma por série: o laço
        // abaixo percorre TODAS as séries do usuário, e perguntar ao banco a cada
        // volta era o que segurava a Home em segundos (ver o repositório)
        Set<UUID> linked = incomeSourceRepository.findLinkedSeriesIds(userId);
        for (RecurringSeries series : recurringSeriesRepository.findAllByUserId(userId)) {
            if (series.getFlow() != RecurringSeries.Flow.INCOME) continue;
            if (!series.isActive() || series.isDismissed()) continue;
            if (series.getCadence() == RecurringSeries.Cadence.IRREGULAR) continue;
            if (linked.contains(series.getId())) continue;

            String label = series.getDisplayName() != null
                    ? series.getDisplayName() : series.getMerchantKey();
            out.add(new WishResponses.Suggestion(
                    series.getId(),
                    guessKind(label + " " + series.getMerchantKey()).name(),
                    label,
                    series.getExpectedAmount(),
                    series.getAnchorDay()));
        }
        return out;
    }

    /**
     * Chuta o tipo pelo rótulo do extrato. Erro aqui é barato — o usuário
     * corrige na confirmação —, e acertar poupa a pergunta mais chata da tela.
     */
    static IncomeSource.Kind guessKind(String label) {
        String text = normalize(label);
        if (containsAny(text, MEAL_HINTS)) return IncomeSource.Kind.MEAL_VOUCHER;
        if (containsAny(text, FOOD_HINTS)) return IncomeSource.Kind.FOOD_VOUCHER;
        // adiantamento antes de salário: "adiantamento salarial" tem as duas
        // palavras, e o que manda é a mais específica
        if (containsAny(text, ADVANCE_HINTS)) return IncomeSource.Kind.ADVANCE;
        if (containsAny(text, SALARY_HINTS)) return IncomeSource.Kind.SALARY;
        return IncomeSource.Kind.OTHER;
    }

    @Transactional
    public WishResponses.IncomeSourceItem create(String email, WishRequests.CreateIncomeSource request) {
        User user = requireUser(email);
        IncomeSource.Kind kind = parseKind(request.kind());
        String name = request.name().trim();

        incomeSourceRepository.findByUserIdAndKindAndName(user.getId(), kind, name)
                .ifPresent(existing -> {
                    throw new ResourceConflictException(
                            "Já existe uma fonte de renda desse tipo com o nome \"" + name + "\"");
                });

        IncomeSource source = IncomeSource.builder()
                .user(user)
                .kind(kind)
                .name(name)
                .expectedAmount(request.expectedAmount())
                .anchorDay(request.anchorDay() != null ? request.anchorDay().shortValue() : null)
                // cadastro manual é declaração do usuário: nasce confirmado a
                // menos que ele diga o contrário
                .confirmed(request.confirmed() == null || request.confirmed())
                .active(true)
                .build();
        return WishResponses.IncomeSourceItem.from(incomeSourceRepository.save(source));
    }

    /**
     * Aceita uma sugestão do extrato, virando fonte confirmada. Idempotente por
     * série: reexecutar não cria a segunda fonte para o mesmo salário.
     */
    @Transactional
    public WishResponses.IncomeSourceItem acceptSuggestion(String email, UUID seriesId,
                                                           WishRequests.CreateIncomeSource request) {
        User user = requireUser(email);
        RecurringSeries series = recurringSeriesRepository.findByIdAndUserId(seriesId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Série recorrente não encontrada"));
        if (incomeSourceRepository.existsByUserIdAndSeriesId(user.getId(), seriesId)) {
            throw new ResourceConflictException("Essa série já virou uma fonte de renda");
        }

        String label = series.getDisplayName() != null ? series.getDisplayName() : series.getMerchantKey();
        IncomeSource.Kind kind = request != null && request.kind() != null
                ? parseKind(request.kind())
                : guessKind(label + " " + series.getMerchantKey());
        String name = request != null && request.name() != null && !request.name().isBlank()
                ? request.name().trim() : label;

        // Colisão de nome com uma fonte que o usuário já cadastrou à mão: o
        // sufixo mantém o aceite funcionando em vez de travar a tela num 409
        String finalName = name;
        int suffix = 2;
        while (incomeSourceRepository.findByUserIdAndKindAndName(user.getId(), kind, finalName).isPresent()) {
            finalName = name + " (" + suffix++ + ")";
        }

        BigDecimal amount = request != null && request.expectedAmount() != null
                ? request.expectedAmount() : series.getExpectedAmount();
        Short anchor = request != null && request.anchorDay() != null
                ? request.anchorDay().shortValue() : series.getAnchorDay();

        IncomeSource source = IncomeSource.builder()
                .user(user)
                .kind(kind)
                .name(finalName)
                .expectedAmount(amount)
                .anchorDay(anchor)
                // aceitar a sugestão É a confirmação
                .confirmed(true)
                .active(true)
                .seriesId(seriesId)
                .build();
        return WishResponses.IncomeSourceItem.from(incomeSourceRepository.save(source));
    }

    @Transactional
    public WishResponses.IncomeSourceItem update(String email, UUID id, WishRequests.UpdateIncomeSource request) {
        User user = requireUser(email);
        IncomeSource source = incomeSourceRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Fonte de renda não encontrada"));

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Nome da fonte não pode ser vazio");
            incomeSourceRepository.findByUserIdAndKindAndName(user.getId(), source.getKind(), name)
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new ResourceConflictException(
                                "Já existe uma fonte de renda desse tipo com o nome \"" + name + "\"");
                    });
            source.setName(name);
        }
        if (request.expectedAmount() != null) source.setExpectedAmount(request.expectedAmount());
        if (request.anchorDay() != null) source.setAnchorDay(request.anchorDay().shortValue());
        if (request.confirmed() != null) source.setConfirmed(request.confirmed());
        if (request.active() != null) source.setActive(request.active());

        return WishResponses.IncomeSourceItem.from(incomeSourceRepository.save(source));
    }

    @Transactional
    public void delete(String email, UUID id) {
        User user = requireUser(email);
        IncomeSource source = incomeSourceRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Fonte de renda não encontrada"));
        incomeSourceRepository.delete(source);
    }

    /** PUT: cria ou substitui — a jornada é uma só por pessoa. */
    @Transactional
    public WishResponses.WorkProfileItem saveWorkProfile(String email, WishRequests.SaveWorkProfile request) {
        User user = requireUser(email);
        WorkProfile profile = workProfileRepository.findById(user.getId())
                .orElseGet(() -> WorkProfile.builder().userId(user.getId()).build());
        profile.setDaysPerWeek(request.daysPerWeek().shortValue());
        profile.setHoursPerDay(request.hoursPerDay());

        WorkProfile stored = workProfileRepository.save(profile);
        return WishResponses.WorkProfileItem.from(stored, hoursPerMonth(stored));
    }

    private BigDecimal hoursPerMonth(WorkProfile profile) {
        if (profile == null) return null;
        return profile.getHoursPerDay()
                .multiply(BigDecimal.valueOf(profile.getDaysPerWeek()))
                .multiply(WEEKS_PER_MONTH)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private IncomeSource.Kind parseKind(String raw) {
        try {
            return IncomeSource.Kind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo inválido: use SALARY, MEAL_VOUCHER, FOOD_VOUCHER, ADVANCE ou OTHER");
        }
    }

    /** Sem acento e em minúsculas: o extrato escreve "SALÁRIO" e "SALARIO". */
    private static String normalize(String value) {
        if (value == null) return "";
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // espaço nas pontas para que os palpites com espaço ("vr ") casem
        // também quando a palavra é o rótulo inteiro
        return " " + stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    private static boolean containsAny(String text, List<String> hints) {
        for (String hint : hints) {
            if (text.contains(hint)) return true;
        }
        return false;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
