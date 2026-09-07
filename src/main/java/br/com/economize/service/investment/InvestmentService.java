package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.model.InvestmentPosition;
import br.com.economize.model.InvestmentPosition.Indexer;
import br.com.economize.model.InvestmentPosition.Source;
import br.com.economize.model.InvestmentPosition.Type;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.InvestmentPositionRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.connector.pluggy.PluggyClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * As posições de investimento do usuário: o sync com o agregador, o cadastro
 * manual e o resumo que junta as duas coisas.
 *
 * <p><b>Sync sem apagar.</b> A posição que sumiu da resposta do provedor NÃO é
 * removida: o Pluggy oscila (conexão pendente de MFA devolve lista vazia) e
 * apagar o CDB do usuário por causa de uma sync ruim seria pior do que
 * mostrá-lo com data velha. Ela só para de receber {@code positionDate}; a
 * resposta a marca como desatualizada e quem decide removê-la é o usuário.
 *
 * <p><b>Conector ausente é 503, não 400.</b> Com {@code PLUGGY_ENABLED=false}
 * o bean do cliente nem existe; o pedido está correto e a instalação é que não
 * o atende — a mesma leitura do cofre de chaves (EC-107). O resto do módulo
 * (manual, movimentos, resumo, perfil) segue funcionando sem o conector.
 */
@Slf4j
@Service
public class InvestmentService {

    /**
     * Uma posição do conector sem atualização há mais de uma semana está
     * desatualizada. O sync é manual e ninguém o roda todo dia; sete dias
     * separam "não sincronizei esta semana" de "esta posição sumiu de lá".
     */
    static final int STALE_AFTER_DAYS = 7;

    private static final int NAME_MAX = 160;
    private static final int INSTITUTION_MAX = 160;

    private final InvestmentPositionRepository positionRepository;
    private final PluggyItemRepository pluggyItemRepository;
    private final UserRepository userRepository;
    private final InvestmentMovementService movementService;
    // presente só com economize.pluggy.enabled=true
    private final ObjectProvider<PluggyClient> pluggyClient;
    private final int maxManualPositions;

    public InvestmentService(InvestmentPositionRepository positionRepository,
                             PluggyItemRepository pluggyItemRepository,
                             UserRepository userRepository,
                             InvestmentMovementService movementService,
                             ObjectProvider<PluggyClient> pluggyClient,
                             @Value("${economize.investments.max-manual-positions:200}") int maxManualPositions) {
        this.positionRepository = positionRepository;
        this.pluggyItemRepository = pluggyItemRepository;
        this.userRepository = userRepository;
        this.movementService = movementService;
        this.pluggyClient = pluggyClient;
        this.maxManualPositions = maxManualPositions;
    }

    // ------------------------------------------------------------------ sync

    public InvestmentResponses.SyncResult sync(String email) {
        User user = requireUser(email);
        PluggyClient client = pluggyClient.getIfAvailable();
        if (client == null) {
            throw new ServiceUnavailableException(
                    "Conector Pluggy desativado nesta instalação — defina PLUGGY_ENABLED=true para "
                            + "sincronizar posições de investimento. Cadastro manual, movimentos e resumo "
                            + "continuam disponíveis.");
        }
        if (!client.isConfigured()) {
            throw new IllegalArgumentException(
                    "Conector Pluggy sem credenciais — defina PLUGGY_CLIENT_ID e PLUGGY_CLIENT_SECRET");
        }
        List<PluggyItem> items = pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
        if (items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nenhuma conexão Pluggy registrada — conecte uma instituição pelo app antes de sincronizar");
        }

        String apiKey = client.authenticate();
        int created = 0, updated = 0, itemsRead = 0, skippedItems = 0, skippedPositions = 0;
        WebClientResponseException lastFailure = null;

        for (PluggyItem item : items) {
            List<Map<String, Object>> raw;
            try {
                raw = client.investments(apiKey, item.getItemId());
            } catch (WebClientResponseException e) {
                // uma conexão quebrada (credencial expirada, MFA pendente) não
                // pode impedir as outras de sincronizar; nada dela é apagado
                skippedItems++;
                lastFailure = e;
                log.warn("Pluggy recusou a leitura de investimentos de uma conexão (conector=\"{}\", status={}); "
                        + "as demais seguem", item.getConnectorName(), e.getStatusCode().value());
                continue;
            }
            itemsRead++;
            for (Map<String, Object> entry : raw) {
                PluggyInvestmentMapper.Mapped mapped = PluggyInvestmentMapper.map(entry);
                if (mapped == null) {
                    // sem id não há upsert possível: gravar criaria uma linha
                    // nova a cada sync
                    skippedPositions++;
                    log.warn("Posição sem id na resposta do Pluggy — ignorada (conector=\"{}\")",
                            item.getConnectorName());
                    continue;
                }
                if (upsert(user, item, mapped)) created++;
                else updated++;
            }
        }

        if (itemsRead == 0 && lastFailure != null) {
            // TODAS as conexões falharam: aí não é "uma conexão ruim", é o
            // provedor fora ou credencial da aplicação errada — o 502 do handler
            // é a resposta honesta, com o motivo em log
            throw lastFailure;
        }
        log.info("Sync de investimentos: {} criadas, {} atualizadas em {} conexão(ões) para user={}",
                created, updated, itemsRead, email);
        return new InvestmentResponses.SyncResult(created + updated, created, updated, itemsRead,
                skippedItems, skippedPositions);
    }

    /** Verdadeiro quando criou; falso quando atualizou uma existente. */
    private boolean upsert(User user, PluggyItem item, PluggyInvestmentMapper.Mapped mapped) {
        InvestmentPosition known = positionRepository
                .findByUserIdAndSourceAndProviderPositionId(user.getId(), Source.CONNECTOR, mapped.providerId())
                .orElse(null);
        if (known != null) {
            apply(known, item, mapped);
            positionRepository.save(known);
            return false;
        }
        InvestmentPosition fresh = InvestmentPosition.builder()
                .user(user)
                .source(Source.CONNECTOR)
                .providerPositionId(mapped.providerId())
                .build();
        apply(fresh, item, mapped);
        try {
            // saveAndFlush: duas syncs simultâneas do mesmo usuário disputam o
            // unique parcial (user, source, provider_position_id). A violação
            // tem que estourar AQUI para o catch reaproveitar a linha do
            // vencedor — mesmo padrão de ConnectorAccountService.insert
            positionRepository.saveAndFlush(fresh);
            return true;
        } catch (DataIntegrityViolationException race) {
            log.info("Posição já registrada por uma sincronização concorrente — reaproveitando");
            InvestmentPosition winner = positionRepository
                    .findByUserIdAndSourceAndProviderPositionId(user.getId(), Source.CONNECTOR, mapped.providerId())
                    .orElseThrow(() -> race);
            apply(winner, item, mapped);
            positionRepository.save(winner);
            return false;
        }
    }

    /**
     * Copia o retrato do provedor para a linha. A instituição é o CONECTOR
     * (onde a posição está custodiada), não o emissor do papel: para o usuário
     * "Banco Inter" é onde ele vê o CDB, mesmo que o emissor seja outro banco.
     * O emissor entra só como rede, quando o conector não tem nome.
     */
    private static void apply(InvestmentPosition p, PluggyItem item, PluggyInvestmentMapper.Mapped m) {
        p.setPluggyItemId(item.getId());
        String institution = item.getConnectorName() != null ? item.getConnectorName() : m.issuer();
        p.setInstitution(truncate(institution, INSTITUTION_MAX));
        p.setName(m.name());
        p.setCode(m.code());
        p.setType(m.type());
        p.setSubtype(m.subtype());
        p.setIndexer(m.indexer());
        p.setRate(m.rate());
        p.setCurrency(m.currency());
        p.setQuantity(m.quantity());
        p.setUnitPrice(m.unitPrice());
        p.setInvestedAmount(m.investedAmount());
        p.setCurrentValue(m.currentValue());
        p.setMaturityDate(m.maturityDate());
        // sem data no provedor, a posição é "de hoje": foi lida agora
        p.setPositionDate(m.positionDate() != null ? m.positionDate() : LocalDate.now(ZoneOffset.UTC));
    }

    // -------------------------------------------------------------- posições

    public List<InvestmentResponses.PositionItem> list(String email) {
        User user = requireUser(email);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return positionRepository.findAllByUserIdOrderByNameAsc(user.getId()).stream()
                .map(p -> InvestmentResponses.PositionItem.from(p, isStale(p, today)))
                .toList();
    }

    public InvestmentResponses.PositionItem create(String email, InvestmentRequests.CreatePosition request) {
        User user = requireUser(email);
        if (positionRepository.countByUserIdAndSource(user.getId(), Source.MANUAL) >= maxManualPositions) {
            throw new IllegalArgumentException(
                    "Limite de " + maxManualPositions + " posições manuais atingido — remova alguma antes de cadastrar outra");
        }
        InvestmentPosition position = InvestmentPosition.builder()
                .user(user)
                .source(Source.MANUAL)
                .name(requireName(request.name()))
                .type(parseType(request.type()))
                .subtype(trimToNull(request.subtype()))
                .indexer(parseIndexer(request.indexer()))
                .rate(trimToNull(request.rate()))
                .code(normalizeCode(request.code()))
                .institution(truncate(trimToNull(request.institution()), INSTITUTION_MAX))
                .currency(normalizeCurrency(request.currency()))
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .investedAmount(request.investedAmount())
                .currentValue(request.currentValue())
                .maturityDate(request.maturityDate())
                .positionDate(request.positionDate())
                .build();
        InvestmentPosition stored = positionRepository.save(position);
        return InvestmentResponses.PositionItem.from(stored, false);
    }

    /**
     * Só a posição MANUAL aceita edição. A do conector é substituída inteira
     * na próxima sync: aceitar o PATCH seria aceitar uma edição que dura até o
     * próximo clique em "sincronizar" — e o usuário não teria como saber.
     */
    public InvestmentResponses.PositionItem update(String email, UUID id, InvestmentRequests.UpdatePosition request) {
        User user = requireUser(email);
        InvestmentPosition position = requireOwned(user.getId(), id);
        if (!position.isManual()) {
            throw new IllegalArgumentException(
                    "Posição sincronizada pelo conector não pode ser editada — os dados vêm do provedor. "
                            + "Para corrigir, ajuste na instituição e sincronize de novo, ou cadastre uma posição manual.");
        }
        if (request.name() != null) position.setName(requireName(request.name()));
        if (request.type() != null) position.setType(parseType(request.type()));
        if (request.subtype() != null) position.setSubtype(trimToNull(request.subtype()));
        if (request.indexer() != null) position.setIndexer(parseIndexer(request.indexer()));
        if (request.rate() != null) position.setRate(trimToNull(request.rate()));
        if (request.code() != null) position.setCode(normalizeCode(request.code()));
        if (request.institution() != null) {
            position.setInstitution(truncate(trimToNull(request.institution()), INSTITUTION_MAX));
        }
        if (request.currency() != null) position.setCurrency(normalizeCurrency(request.currency()));
        if (request.quantity() != null) position.setQuantity(request.quantity());
        if (request.unitPrice() != null) position.setUnitPrice(request.unitPrice());
        if (request.investedAmount() != null) position.setInvestedAmount(request.investedAmount());
        if (request.currentValue() != null) position.setCurrentValue(request.currentValue());
        if (request.maturityDate() != null) position.setMaturityDate(request.maturityDate());
        if (request.positionDate() != null) position.setPositionDate(request.positionDate());

        InvestmentPosition stored = positionRepository.save(position);
        return InvestmentResponses.PositionItem.from(stored, false);
    }

    /**
     * Qualquer origem pode ser removida — inclusive a do conector, que é como
     * o usuário se livra de uma posição que sumiu do provedor e ficou
     * desatualizada. Se ela ainda existir lá, a próxima sync a recria; é o
     * comportamento esperado, e está dito na documentação da rota.
     */
    public void delete(String email, UUID id) {
        User user = requireUser(email);
        positionRepository.delete(requireOwned(user.getId(), id));
    }

    // ---------------------------------------------------------------- resumo

    public InvestmentResponses.Summary summary(String email) {
        User user = requireUser(email);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<InvestmentPosition> positions = positionRepository.findAllByUserIdOrderByNameAsc(user.getId());

        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal currentValue = BigDecimal.ZERO;
        BigDecimal pricedInvested = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        int priced = 0;
        int pairs = 0;
        int stale = 0;
        OffsetDateTime updatedAt = null;
        TreeSet<String> needsQuote = new TreeSet<>();
        LinkedHashMap<String, BigDecimal> byType = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> byInstitution = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> byIndexer = new LinkedHashMap<>();
        TreeSet<Source> sources = new TreeSet<>();

        for (InvestmentPosition p : positions) {
            sources.add(p.getSource());
            if (isStale(p, today)) stale++;
            if (p.getUpdatedAt() != null && (updatedAt == null || p.getUpdatedAt().isAfter(updatedAt))) {
                updatedAt = p.getUpdatedAt();
            }
            if (p.getInvestedAmount() != null) totalInvested = totalInvested.add(p.getInvestedAmount());
            if (p.getCurrentValue() == null) {
                if (p.getCode() != null) needsQuote.add(p.getCode());
                continue;
            }
            priced++;
            currentValue = currentValue.add(p.getCurrentValue());
            byType.merge(p.getType().name(), p.getCurrentValue(), BigDecimal::add);
            byInstitution.merge(p.getInstitution() != null ? p.getInstitution() : "Não informada",
                    p.getCurrentValue(), BigDecimal::add);
            byIndexer.merge(p.getIndexer() != null ? p.getIndexer().name() : Indexer.NONE.name(),
                    p.getCurrentValue(), BigDecimal::add);
            if (p.getInvestedAmount() != null) {
                // lucro só onde os DOIS lados são conhecidos: somar o aplicado
                // da ETF sem cotação faria o total parecer um prejuízo
                pairs++;
                pricedInvested = pricedInvested.add(p.getInvestedAmount());
                profit = profit.add(p.getCurrentValue().subtract(p.getInvestedAmount()));
            }
        }

        BigDecimal profitPercent = pairs > 0 && pricedInvested.signum() != 0
                ? profit.multiply(BigDecimal.valueOf(100)).divide(pricedInvested, 2, RoundingMode.HALF_UP)
                : null;

        InvestmentResponses.Movements movements = movementService.movements(user, 12);
        InvestmentResponses.MovementTotals totals = movements.totals();

        return new InvestmentResponses.Summary(
                totalInvested,
                currentValue,
                pairs > 0 ? profit : null,
                profitPercent,
                positions.size(),
                priced,
                slices(byType, currentValue, (key, value, share) ->
                        new InvestmentResponses.TypeSlice(key, InvestmentResponses.typeLabel(Type.valueOf(key)), value, share)),
                slices(byInstitution, currentValue, InvestmentResponses.InstitutionSlice::new),
                slices(byIndexer, currentValue, InvestmentResponses.IndexerSlice::new),
                updatedAt,
                stale,
                sources.stream().map(Enum::name).toList(),
                new InvestmentResponses.MovementTotals12m(totals.applied(), totals.redeemed(), totals.yield(),
                        movements.netInvested()),
                new ArrayList<>(needsQuote));
    }

    private interface SliceFactory<T> {
        T create(String key, BigDecimal value, BigDecimal share);
    }

    /** Fatias ordenadas da maior para a menor, com participação de 0 a 1 sobre o total conhecido. */
    private static <T> List<T> slices(Map<String, BigDecimal> sums, BigDecimal total, SliceFactory<T> factory) {
        List<Map.Entry<String, BigDecimal>> entries = new ArrayList<>(sums.entrySet());
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<T> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : entries) {
            BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO
                    : e.getValue().divide(total, 4, RoundingMode.HALF_UP);
            out.add(factory.create(e.getKey(), e.getValue(), share));
        }
        return out;
    }

    static boolean isStale(InvestmentPosition p, LocalDate today) {
        if (p.isManual()) return false;
        return p.getPositionDate() == null || p.getPositionDate().isBefore(today.minusDays(STALE_AFTER_DAYS));
    }

    // ------------------------------------------------------------ validação

    private InvestmentPosition requireOwned(UUID userId, UUID id) {
        return positionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Posição não encontrada"));
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Nome da posição não pode ser vazio");
        return truncate(name, NAME_MAX);
    }

    static Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Tipo é obrigatório: use " + names(Type.values()));
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Tipo inválido: use " + names(Type.values()));
        }
    }

    static Indexer parseIndexer(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Indexer.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Indexador inválido: use " + names(Indexer.values()));
        }
    }

    /** Ticker sempre maiúsculo: "vt" e "VT" são a mesma ETF, e a cotação é buscada pelo código. */
    static String normalizeCode(String raw) {
        String code = trimToNull(raw);
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }

    static String normalizeCurrency(String raw) {
        String currency = trimToNull(raw);
        if (currency == null) return "BRL";
        String upper = currency.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Moeda inválida: use um código ISO de 3 letras (BRL, USD...)");
        }
        return upper;
    }

    private static String names(Enum<?>[] values) {
        return String.join(", ", Arrays.stream(values).map(Enum::name).toList());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
