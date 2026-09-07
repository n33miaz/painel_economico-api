package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.InvestmentInterest;
import br.com.economize.model.InvestmentPosition;
import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.User;
import br.com.economize.repository.InvestmentInterestRepository;
import br.com.economize.repository.InvestmentPositionRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A PERSONALIZAÇÃO: o que este usuário acompanha, derivado do que ele TEM.
 *
 * <p>O pedido do dono foi literal — "exibir o que normalmente ele utiliza: tudo
 * o que interfere no CDB/CDI, Tesouro Direto e a ETF Vanguard Total". Quem tem
 * CDB acompanha o CDI sem precisar dizer; quem tem Tesouro IPCA+ acompanha a
 * inflação; quem tem ETF no exterior acompanha o dólar e o ticker. O perfil
 * lê as posições e os movimentos e deduz — e o que a pessoa acompanha SEM ter
 * na carteira ela declara em {@code interests}, que é o único pedaço que se
 * remove.
 *
 * <p>Os tópicos saem de um VOCABULÁRIO FIXO, o mesmo que o agregador de
 * notícias entende. Inventar tópico aqui seria prometer ao app um filtro que
 * nenhum feed atende.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentProfileService {

    /** O vocabulário de tópicos de notícia, e só ele. A ordem é a de exibição. */
    static final List<String> TOPIC_VOCABULARY = List.of(
            "selic-cdi", "tesouro", "inflacao", "cambio", "bolsa", "etf-exterior", "cripto",
            "fiis", "renda-fixa", "previdencia", "macro-br", "macro-global", "financas-pessoais");

    /** Sem posição, movimento ou interesse: o que qualquer pessoa que paga conta acompanha. */
    static final List<String> DEFAULT_TOPICS = List.of("macro-br", "financas-pessoais", "selic-cdi");

    /** Janela de movimentos lida para derivar o perfil — um ano cobre o ciclo de qualquer aplicação. */
    private static final int MOVEMENT_MONTHS = 12;

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9.\\-_]{0,31}");

    // vocabulário sobre a descrição normalizada dos movimentos (ver
    // InvestmentMovementService): cada balde deriva os mesmos interesses que a
    // posição equivalente derivaria
    private static final Pattern FIXED_INCOME_TERMS = Pattern.compile("\\b(?:cdb|rdb|lci|lca|renda fixa|aplic(?!ativo)\\w*)\\b");
    private static final Pattern TREASURY_TERMS = Pattern.compile("\\btesouro\\b");
    private static final Pattern SAVINGS_TERMS = Pattern.compile("\\bpoupan\\w*\\b");
    private static final Pattern CRYPTO_TERMS = Pattern.compile("\\b(?:cripto\\w*|bitcoin|binance|btc|eth)\\b");
    private static final Pattern PENSION_TERMS = Pattern.compile("\\b(?:previd\\w*|pgbl|vgbl)\\b");
    private static final Pattern FII_TERMS = Pattern.compile("\\b(?:fii|fiis|fundo imobiliario)\\b");
    private static final Pattern EQUITY_TERMS = Pattern.compile("\\b(?:acoes|dividend\\w*|jcp|etfs?|corretora|b3)\\b");

    private final InvestmentPositionRepository positionRepository;
    private final InvestmentInterestRepository interestRepository;
    private final InvestmentMovementService movementService;
    private final UserRepository userRepository;

    public InvestmentResponses.Profile profile(String email) {
        User user = requireUser(email);
        List<InvestmentPosition> positions = positionRepository.findAllByUserIdOrderByNameAsc(user.getId());
        List<InvestmentInterest> interests = interestRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
        List<String> descriptions = movementService.normalizedDescriptions(user, MOVEMENT_MONTHS);
        return derive(positions, descriptions, interests);
    }

    /**
     * Pura, para o teste montar posições e movimentos sem banco. Os interesses
     * MANUAIS entram primeiro, de propósito: quando o mesmo item também é
     * derivado, a resposta o mostra como MANUAL — é o que o usuário declarou e
     * é o que ele pode remover; removido, o derivado assume sozinho.
     */
    InvestmentResponses.Profile derive(List<InvestmentPosition> positions, List<String> normalizedMovements,
                                       List<InvestmentInterest> interests) {
        Accumulator acc = new Accumulator();

        for (InvestmentInterest interest : interests) {
            String code = interest.getCode();
            switch (interest.getKind()) {
                case RATE, INDEX, CURRENCY -> {
                    acc.indexers.add(code);
                    acc.watch(interest.getKind().name(), code, null, "MANUAL");
                }
                case TICKER -> acc.watch("TICKER", code, interest.getMarket(), "MANUAL");
                case TOPIC -> acc.topics.add(code);
            }
            acc.manualReasons.add(interest.getKind().name() + " " + code
                    + (interest.getMarket() != null ? " (" + interest.getMarket() + ")" : "") + " — declarado por você");
        }

        for (InvestmentPosition position : positions) {
            derivePosition(position, acc);
        }
        deriveMovements(normalizedMovements, acc);

        boolean derivedSomething = !acc.positionReasons.isEmpty() || !acc.movementReasons.isEmpty();
        boolean isDefault = !derivedSomething && interests.isEmpty();
        List<String> topics;
        String note = null;
        if (isDefault) {
            topics = DEFAULT_TOPICS;
            note = "Sem posições, movimentos de investimento no extrato ou interesses declarados: "
                    + "perfil padrão. Cadastre uma posição, sincronize o conector ou declare um interesse.";
        } else {
            // macro-br é de todo mundo que investe no Brasil; entra por último
            // para a ordem refletir o que foi derivado antes do que é geral
            acc.topics.add("macro-br");
            topics = orderTopics(acc.topics);
        }

        return new InvestmentResponses.Profile(
                new ArrayList<>(acc.indexers),
                new ArrayList<>(acc.watch.values()),
                topics,
                new InvestmentResponses.DerivedFrom(acc.positionReasons, acc.movementReasons, acc.manualReasons, note),
                isDefault);
    }

    private void derivePosition(InvestmentPosition p, Accumulator acc) {
        LinkedHashSet<String> gained = new LinkedHashSet<>();
        Indexer indexer = p.getIndexer();
        boolean foreign = indexer == Indexer.USD || (p.getCurrency() != null && !"BRL".equalsIgnoreCase(p.getCurrency()));

        switch (p.getType()) {
            case FIXED_INCOME -> {
                Indexer rate = indexer == null || indexer == Indexer.NONE || indexer == Indexer.PREFIXADO
                        ? Indexer.CDI : indexer;
                acc.indicator(rate, gained);
                acc.topic("renda-fixa", gained);
                acc.topic("selic-cdi", gained);
                if (rate == Indexer.IPCA) acc.topic("inflacao", gained);
            }
            case TREASURY -> {
                acc.indicator(Indexer.SELIC, gained);
                acc.indicator(Indexer.IPCA, gained);
                acc.topic("tesouro", gained);
                acc.topic("selic-cdi", gained);
                acc.topic("inflacao", gained);
            }
            case ETF, EQUITY -> {
                if (foreign) {
                    acc.indicator(Indexer.USD, gained);
                    acc.topic(p.getType() == InvestmentPosition.Type.ETF ? "etf-exterior" : "bolsa", gained);
                    acc.topic("cambio", gained);
                    acc.topic("macro-global", gained);
                } else {
                    acc.topic("bolsa", gained);
                }
                if (p.getCode() != null) {
                    String market = foreign ? "US" : "BR";
                    acc.watch("TICKER", p.getCode(), market, "DERIVED");
                    gained.add(p.getCode());
                }
            }
            case FUND -> {
                String subtype = p.getSubtype() != null ? p.getSubtype().toUpperCase(Locale.ROOT) : "";
                if (subtype.contains("FII") || subtype.contains("REAL_ESTATE") || subtype.contains("IMOBILI")) {
                    acc.topic("fiis", gained);
                } else if (indexer == Indexer.CDI || indexer == Indexer.SELIC) {
                    acc.indicator(indexer, gained);
                    acc.topic("renda-fixa", gained);
                    acc.topic("selic-cdi", gained);
                }
            }
            case CRYPTO -> {
                acc.topic("cripto", gained);
                if (p.getCode() != null) {
                    acc.watch("TICKER", p.getCode(), "CRYPTO", "DERIVED");
                    gained.add(p.getCode());
                }
            }
            case PENSION -> acc.topic("previdencia", gained);
            case OTHER -> {
                // sem tipo conhecido só o indexador explícito diz algo
            }
        }
        // o indexador explícito vale para qualquer tipo: um COE atrelado ao IPCA
        // acompanha a inflação do mesmo jeito
        if (indexer != null && indexer != Indexer.NONE && indexer != Indexer.PREFIXADO) {
            acc.indicator(indexer, gained);
        }

        if (!gained.isEmpty()) {
            acc.positionReasons.add(p.getName() + (p.getInstitution() != null ? " (" + p.getInstitution() + ")" : "")
                    + " → " + String.join(", ", gained));
        }
    }

    private void deriveMovements(List<String> descriptions, Accumulator acc) {
        int fixedIncome = 0, treasury = 0, savings = 0, crypto = 0, pension = 0, fii = 0, equity = 0;
        for (String text : descriptions) {
            if (text == null) continue;
            if (FIXED_INCOME_TERMS.matcher(text).find()) fixedIncome++;
            if (TREASURY_TERMS.matcher(text).find()) treasury++;
            if (SAVINGS_TERMS.matcher(text).find()) savings++;
            if (CRYPTO_TERMS.matcher(text).find()) crypto++;
            if (PENSION_TERMS.matcher(text).find()) pension++;
            if (FII_TERMS.matcher(text).find()) fii++;
            else if (EQUITY_TERMS.matcher(text).find()) equity++;
        }

        if (fixedIncome > 0) {
            LinkedHashSet<String> gained = new LinkedHashSet<>();
            acc.indicator(Indexer.CDI, gained);
            acc.topic("renda-fixa", gained);
            acc.topic("selic-cdi", gained);
            acc.movementReasons.add(plural(fixedIncome, "movimento", "movimentos")
                    + " de renda fixa (CDB/LCI/LCA/aplicação) no extrato → CDI, renda-fixa");
        }
        if (treasury > 0) {
            LinkedHashSet<String> gained = new LinkedHashSet<>();
            acc.indicator(Indexer.SELIC, gained);
            acc.indicator(Indexer.IPCA, gained);
            acc.topic("tesouro", gained);
            acc.topic("selic-cdi", gained);
            acc.topic("inflacao", gained);
            acc.movementReasons.add(plural(treasury, "movimento", "movimentos")
                    + " de Tesouro Direto no extrato → SELIC, IPCA, tesouro");
        }
        if (savings > 0) {
            LinkedHashSet<String> gained = new LinkedHashSet<>();
            acc.indicator(Indexer.SELIC, gained);
            acc.topic("renda-fixa", gained);
            acc.topic("selic-cdi", gained);
            acc.movementReasons.add(plural(savings, "movimento", "movimentos")
                    + " de poupança no extrato → SELIC, renda-fixa");
        }
        if (crypto > 0) {
            acc.topics.add("cripto");
            acc.movementReasons.add(plural(crypto, "movimento", "movimentos") + " de cripto no extrato → cripto");
        }
        if (pension > 0) {
            acc.topics.add("previdencia");
            acc.movementReasons.add(plural(pension, "movimento", "movimentos")
                    + " de previdência no extrato → previdencia");
        }
        if (fii > 0) {
            acc.topics.add("fiis");
            acc.movementReasons.add(plural(fii, "movimento", "movimentos") + " de FII no extrato → fiis");
        }
        if (equity > 0) {
            acc.topics.add("bolsa");
            acc.movementReasons.add(plural(equity, "movimento", "movimentos")
                    + " de bolsa (ações/dividendos/ETF) no extrato → bolsa");
        }
    }

    // ------------------------------------------------------------ interesses

    /**
     * Idempotente: declarar duas vezes o mesmo interesse devolve o existente,
     * porque na tela isto é um botão "acompanhar" e tocar nele duas vezes não
     * pode virar erro.
     */
    public InvestmentResponses.InterestItem addInterest(String email, InvestmentRequests.CreateInterest request) {
        User user = requireUser(email);
        InvestmentInterest.Kind kind = parseKind(request.kind());
        String code = normalizeCode(kind, request.code());
        String market = kind == InvestmentInterest.Kind.TICKER ? normalizeMarket(request.market()) : null;

        InvestmentInterest existing = interestRepository
                .findByUserIdAndKindAndCode(user.getId(), kind, code).orElse(null);
        if (existing != null) return InvestmentResponses.InterestItem.from(existing);

        try {
            // saveAndFlush: dois toques simultâneos disputam o unique (user,
            // kind, code); a violação precisa estourar AQUI para reaproveitar a
            // linha do vencedor (mesmo padrão de ConnectorAccountService)
            return InvestmentResponses.InterestItem.from(interestRepository.saveAndFlush(InvestmentInterest.builder()
                    .user(user)
                    .kind(kind)
                    .code(code)
                    .market(market)
                    .build()));
        } catch (DataIntegrityViolationException race) {
            return interestRepository.findByUserIdAndKindAndCode(user.getId(), kind, code)
                    .map(InvestmentResponses.InterestItem::from)
                    .orElseThrow(() -> race);
        }
    }

    public void removeInterest(String email, String kindRaw, String codeRaw) {
        User user = requireUser(email);
        InvestmentInterest.Kind kind = parseKind(kindRaw);
        String code = normalizeCode(kind, codeRaw);
        InvestmentInterest interest = interestRepository.findByUserIdAndKindAndCode(user.getId(), kind, code)
                .orElseThrow(() -> new ResourceNotFoundException("Interesse não encontrado"));
        interestRepository.delete(interest);
    }

    private static InvestmentInterest.Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Tipo do interesse é obrigatório: use RATE, INDEX, CURRENCY, TICKER ou TOPIC");
        }
        try {
            return InvestmentInterest.Kind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo de interesse inválido: use RATE, INDEX, CURRENCY, TICKER ou TOPIC");
        }
    }

    /**
     * TOPIC é slug minúsculo e precisa existir no vocabulário — um tópico que
     * nenhum feed atende seria um filtro morto na tela. Os demais são códigos
     * maiúsculos (CDI, USD, VT).
     */
    private static String normalizeCode(InvestmentInterest.Kind kind, String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Código do interesse é obrigatório");
        String trimmed = raw.trim();
        if (kind == InvestmentInterest.Kind.TOPIC) {
            String slug = trimmed.toLowerCase(Locale.ROOT);
            if (!TOPIC_VOCABULARY.contains(slug)) {
                throw new IllegalArgumentException("Tópico desconhecido: use um de " + String.join(", ", TOPIC_VOCABULARY));
            }
            return slug;
        }
        String code = trimmed.toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Código inválido: use letras, números, ponto ou traço (até 32 caracteres)");
        }
        return code;
    }

    private static String normalizeMarket(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String market = raw.trim().toUpperCase(Locale.ROOT);
        if (!market.matches("[A-Z0-9]{1,8}")) {
            throw new IllegalArgumentException("Mercado inválido: use um código curto como US ou BR");
        }
        return market;
    }

    private static List<String> orderTopics(Set<String> topics) {
        List<String> ordered = new ArrayList<>();
        for (String topic : topics) if (TOPIC_VOCABULARY.contains(topic)) ordered.add(topic);
        return ordered;
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /** O perfil em construção: conjuntos com ordem de inserção, para a resposta ser estável. */
    private static final class Accumulator {
        final LinkedHashSet<String> indexers = new LinkedHashSet<>();
        final LinkedHashMap<String, InvestmentResponses.WatchItem> watch = new LinkedHashMap<>();
        final LinkedHashSet<String> topics = new LinkedHashSet<>();
        final List<String> positionReasons = new ArrayList<>();
        final List<String> movementReasons = new ArrayList<>();
        final List<String> manualReasons = new ArrayList<>();

        void indicator(Indexer indexer, Set<String> gained) {
            indexers.add(indexer.name());
            watch(kindOf(indexer), indexer.name(), null, "DERIVED");
            gained.add(indexer.name());
        }

        void topic(String topic, Set<String> gained) {
            topics.add(topic);
            gained.add(topic);
        }

        void watch(String kind, String code, String market, String source) {
            watch.putIfAbsent(kind + ":" + code, new InvestmentResponses.WatchItem(kind, code, market, source));
        }

        private static String kindOf(Indexer indexer) {
            return switch (indexer) {
                case CDI, SELIC -> "RATE";
                case IPCA -> "INDEX";
                case USD -> "CURRENCY";
                default -> "RATE";
            };
        }
    }
}
