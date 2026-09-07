package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import br.com.economize.model.MarketSnapshot;
import br.com.economize.repository.MarketSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Guarda o último snapshot bom de cada provedor/ativo para servir como
 * stale-on-error: quando um provedor externo falha (429 de cota, timeout,
 * circuito aberto), cotação defasada é melhor do que lista vazia — sem isso o
 * "Mercado agora" da Home fica em esqueleto.
 *
 * <p>
 * São DUAS camadas. A de cima é memória (Caffeine, 24h, como sempre foi): é
 * dela que sai tudo em operação normal. A de baixo é o banco (V25), gravado em
 * write-through <b>assíncrono</b> a cada snapshot novo e lido só quando a
 * memória não tem a chave — no boot, ou depois que as 24h da memória venceram.
 * A camada de baixo existe porque no plano free o Render reinicia o container o
 * tempo todo, e reiniciar com a cota da AwesomeAPI já estourada deixava a Home
 * vazia até a virada do dia ("sem snapshot stale", no log).
 *
 * <p>
 * Regras de idade: até 24h o snapshot é servido como sempre (marcado stale, que
 * é o que tudo que sai daqui é); entre 24h e 7 dias continua sendo servido, com
 * {@code asOf} explícito em cada item para o app dizer quão velho é; acima de 7
 * dias não sai — preço de uma semana atrás já não informa nada.
 *
 * <p>
 * O banco NUNCA falha uma requisição: gravação em thread do boundedElastic com
 * erro só logado, e leitura protegida por try/catch e por uma memória curta de
 * "não está lá" (para um banco fora do ar não ser consultado a cada miss).
 */
@Slf4j
@Component
public class MarketSnapshotStore {

    /**
     * Prefixo de chave para snapshots vindos de busca do usuário: eles servem
     * de stale para a própria busca, mas não podem entrar no agregado do
     * fallback — senão um ticker pesquisado horas antes apareceria no
     * "Mercado agora" como se fosse item da lista padrão.
     */
    public static final String SEARCH_PREFIX = "search:";

    /**
     * Prefixo dos payloads que NÃO são lista de {@link Indicator} (indicadores
     * macro, títulos do Tesouro, cotação estrangeira, séries históricas). Ficam
     * fora do agregado da Home e são lidos do banco com o tipo que o dono da
     * chave informa — a tabela não guarda tipo, só a chave.
     */
    public static final String DATA_PREFIX = "data:";

    /** Idade máxima servida, de qualquer camada e de qualquer item. */
    public static final Duration MAX_AGE = Duration.ofDays(7);

    /** Validade da camada de memória; vencida, a leitura cai para o banco. */
    static final Duration MEMORY_TTL = Duration.ofHours(24);

    /**
     * Por quanto tempo um miss no banco é lembrado. Sem isso, cada falha de
     * provedor de uma chave que nunca teve snapshot custaria uma ida ao banco
     * — e um banco fora do ar seria consultado em todo miss.
     */
    static final Duration MISS_TTL = Duration.ofMinutes(10);

    private static final int SOURCE_MAX_LENGTH = 40;
    private static final TypeReference<List<Indicator>> INDICATOR_LIST = new TypeReference<>() {
    };

    /** Um payload com sua procedência: quando foi gravado e de onde veio. */
    public record Snapshot<T>(T payload, Instant savedAt, String source) {
    }

    private final Cache<String, Snapshot<Object>> memory = Caffeine.newBuilder()
            .expireAfterWrite(MEMORY_TTL)
            .maximumSize(500)
            .build();

    private final Cache<String, Boolean> knownMisses = Caffeine.newBuilder()
            .expireAfterWrite(MISS_TTL)
            .maximumSize(500)
            .build();

    private final MarketSnapshotRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Scheduler blockingScheduler;

    private volatile boolean hydrated;
    private Instant nextHydrationAttempt = Instant.EPOCH;

    /** Só memória — sem banco. É o modo dos testes de unidade dos provedores. */
    public MarketSnapshotStore() {
        this(null, defaultMapper(), Clock.systemUTC(), Schedulers.boundedElastic());
    }

    @Autowired
    public MarketSnapshotStore(MarketSnapshotRepository repository, ObjectMapper mapper) {
        this(repository, mapper, Clock.systemUTC(), Schedulers.boundedElastic());
    }

    MarketSnapshotStore(MarketSnapshotRepository repository, ObjectMapper mapper, Clock clock,
            Scheduler blockingScheduler) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.blockingScheduler = blockingScheduler;
    }

    /**
     * ObjectMapper equivalente ao do Spring no que importa aqui: datas em ISO e
     * tolerância a campo desconhecido (um snapshot gravado por uma versão mais
     * nova do DTO precisa continuar legível pela anterior).
     */
    static ObjectMapper defaultMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    // ------------------------------------------------------------ boot

    /**
     * Carrega do banco, fora da thread de boot, tudo que ainda vale. É o que faz
     * o primeiro /all depois de um reinício encontrar snapshot em memória em vez
     * de lista vazia.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (repository == null) {
            return;
        }
        Mono.fromRunnable(this::hydrateIfNeeded)
                .subscribeOn(blockingScheduler)
                .subscribe(ignored -> {
                }, error -> log.warn("Snapshots não puderam ser carregados no boot: {}", error.toString()));
    }

    // ------------------------------------------------- listas de Indicator

    public void save(String key, List<Indicator> indicators) {
        save(key, indicators, firstSource(indicators));
    }

    /**
     * Grava em memória agora e no banco em seguida, sem esperar por ele: a
     * resposta ao usuário não pode depender de um INSERT — e não pode falhar
     * porque ele falhou.
     */
    public void save(String key, List<Indicator> indicators, String source) {
        if (indicators == null || indicators.isEmpty()) {
            return;
        }
        put(key, List.copyOf(indicators), source);
    }

    /**
     * Tudo que sai daqui é, por definição, preço velho — então sai já marcado
     * como stale. É essa marca que impede o catálogo de anunciar como "LIVE" um
     * preço que na verdade veio do último snapshot bom. Miss em memória vai ao
     * banco de forma síncrona: é o caminho de exceção (provedor fora do ar), e a
     * memória de misses garante que ele não se repete a cada chamada.
     */
    public Optional<List<Indicator>> find(String key) {
        Snapshot<Object> snapshot = memory.getIfPresent(key);
        if (snapshot == null) {
            snapshot = readFromDb(key, INDICATOR_LIST)
                    .map(found -> new Snapshot<Object>(found.payload(), found.savedAt(), found.source()))
                    .orElse(null);
        }
        return snapshot == null ? Optional.empty() : staleIndicators(key, snapshot);
    }

    /**
     * Mesma leitura de {@link #find}, para quem está dentro de uma cadeia
     * reativa: hit em memória responde no ato; miss vai ao banco em thread do
     * boundedElastic, sem prender o event loop.
     */
    public Mono<List<Indicator>> lookup(String key) {
        Snapshot<Object> snapshot = memory.getIfPresent(key);
        if (snapshot != null) {
            return Mono.justOrEmpty(staleIndicators(key, snapshot));
        }
        if (repository == null || knownMisses.getIfPresent(key) != null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> find(key))
                .subscribeOn(blockingScheduler)
                .flatMap(Mono::justOrEmpty);
    }

    /**
     * Todos os snapshots ainda válidos, agregados — usado pelo fallback do
     * circuit breaker, que não sabe quais chaves cada provedor usa. Se a
     * memória ainda não foi povoada pelo banco (boot), povoa antes. Um mesmo
     * id que apareça em duas chaves entra uma vez só, com o {@code asOf} mais
     * recente: a Home não pode mostrar dois dólares.
     */
    public List<Indicator> findAll() {
        hydrateIfNeeded();
        Map<String, Indicator> byId = new LinkedHashMap<>();
        memory.asMap().forEach((key, snapshot) -> {
            if (key.startsWith(SEARCH_PREFIX) || key.startsWith(DATA_PREFIX)) {
                return;
            }
            staleIndicators(key, snapshot).ifPresent(indicators -> {
                for (int i = 0; i < indicators.size(); i++) {
                    Indicator indicator = indicators.get(i);
                    String id = indicator.getId() != null ? indicator.getId() : key + "#" + i;
                    byId.merge(id, indicator, MarketSnapshotStore::newest);
                }
            });
        });
        return List.copyOf(byId.values());
    }

    // ------------------------------------------------- payloads genéricos

    /**
     * Snapshot de um payload que não é lista de cotação (macro, Tesouro, cotação
     * estrangeira, histórico). A chave PRECISA ter o prefixo {@link #DATA_PREFIX}
     * — é ele que a mantém fora do agregado da Home e que diz ao boot para não
     * tentar lê-la como lista de Indicator.
     */
    public <T> void savePayload(String key, T payload, String source) {
        if (payload == null) {
            return;
        }
        if (!key.startsWith(DATA_PREFIX)) {
            throw new IllegalArgumentException("Chave de payload precisa começar com " + DATA_PREFIX + ": " + key);
        }
        put(key, payload, source);
    }

    /** Leitura síncrona; o mesmo aviso de {@link #find} sobre o miss valer uma ida ao banco. */
    @SuppressWarnings("unchecked")
    public <T> Optional<Snapshot<T>> findPayload(String key, TypeReference<T> type) {
        Snapshot<Object> snapshot = memory.getIfPresent(key);
        if (snapshot != null) {
            return isServable(key, snapshot) ? Optional.of((Snapshot<T>) (Snapshot<?>) snapshot) : Optional.empty();
        }
        return readFromDb(key, type);
    }

    /** Leitura para cadeias reativas: hit em memória no ato, banco fora do event loop. */
    @SuppressWarnings("unchecked")
    public <T> Mono<Snapshot<T>> lookupPayload(String key, TypeReference<T> type) {
        Snapshot<Object> snapshot = memory.getIfPresent(key);
        if (snapshot != null) {
            return isServable(key, snapshot) ? Mono.just((Snapshot<T>) (Snapshot<?>) snapshot) : Mono.empty();
        }
        if (repository == null || knownMisses.getIfPresent(key) != null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> readFromDb(key, type))
                .subscribeOn(blockingScheduler)
                .flatMap(Mono::justOrEmpty);
    }

    // ------------------------------------------------------------ internos

    private void put(String key, Object payload, String source) {
        Instant now = clock.instant();
        String trimmedSource = truncate(source);
        memory.put(key, new Snapshot<>(payload, now, trimmedSource));
        knownMisses.invalidate(key);
        persist(key, payload, now, trimmedSource);
    }

    /**
     * Write-through assíncrono. Serialização e INSERT/UPDATE acontecem na thread
     * do boundedElastic; qualquer erro (banco fora, JSON impossível) vira um
     * WARN e nada mais — a memória já tem o valor e a requisição já respondeu.
     */
    private void persist(String key, Object payload, Instant savedAt, String source) {
        if (repository == null) {
            return;
        }
        Mono.fromRunnable(() -> {
            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException("payload não serializável: " + e.getMessage(), e);
            }
            repository.save(new MarketSnapshot(key, json, OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC), source));
        })
                .subscribeOn(blockingScheduler)
                .subscribe(ignored -> {
                }, error -> log.warn("Snapshot [{}] não persistido ({}); segue só em memória", key,
                        error.toString()));
    }

    private <T> Optional<Snapshot<T>> readFromDb(String key, TypeReference<T> type) {
        if (repository == null || knownMisses.getIfPresent(key) != null) {
            return Optional.empty();
        }
        try {
            Optional<Snapshot<T>> snapshot = repository.findById(key).flatMap(row -> toSnapshot(row, type));
            if (snapshot.isPresent()) {
                Snapshot<T> found = snapshot.get();
                memory.put(key, new Snapshot<>(found.payload(), found.savedAt(), found.source()));
            } else {
                knownMisses.put(key, Boolean.TRUE);
            }
            return snapshot;
        } catch (RuntimeException e) {
            log.warn("Snapshot [{}] não pôde ser lido do banco ({}); seguindo só com a memória", key,
                    e.toString());
            knownMisses.put(key, Boolean.TRUE);
            return Optional.empty();
        }
    }

    private <T> Optional<Snapshot<T>> toSnapshot(MarketSnapshot row, TypeReference<T> type) {
        Instant savedAt = row.getSavedAt() != null ? row.getSavedAt().toInstant() : null;
        if (isExpired(savedAt)) {
            return Optional.empty();
        }
        try {
            T payload = mapper.readValue(row.getPayload(), type);
            return Optional.of(new Snapshot<>(payload, savedAt, row.getSource()));
        } catch (Exception e) {
            // snapshot de uma versão incompatível do DTO: ignorar é melhor que
            // derrubar o fallback inteiro por causa de uma chave
            log.warn("Snapshot [{}] ilegível ({}); ignorado", row.getKey(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Povoa a memória com as listas de cotação do banco. Uma vez por processo
     * quando dá certo; com o banco fora do ar, tenta de novo depois de um
     * intervalo em vez de desistir para sempre.
     */
    private synchronized void hydrateIfNeeded() {
        if (hydrated || repository == null || clock.instant().isBefore(nextHydrationAttempt)) {
            return;
        }
        try {
            int loaded = 0;
            for (MarketSnapshot row : repository.findAll()) {
                String key = row.getKey();
                if (key.startsWith(DATA_PREFIX) || memory.getIfPresent(key) != null) {
                    continue;
                }
                Optional<Snapshot<List<Indicator>>> snapshot = toSnapshot(row, INDICATOR_LIST);
                if (snapshot.isPresent()) {
                    Snapshot<List<Indicator>> found = snapshot.get();
                    memory.put(key, new Snapshot<>(found.payload(), found.savedAt(), found.source()));
                    loaded++;
                }
            }
            hydrated = true;
            if (loaded > 0) {
                log.info("{} snapshot(s) de mercado recuperado(s) do banco", loaded);
            }
        } catch (RuntimeException e) {
            nextHydrationAttempt = clock.instant().plus(MISS_TTL);
            log.warn("Snapshots não puderam ser carregados do banco ({}); nova tentativa em {} min",
                    e.toString(), MISS_TTL.toMinutes());
        }
    }

    /**
     * A lista marcada como stale e datada. Item sem {@code asOf} (gravado antes
     * de o campo existir) recebe o instante da gravação — melhor uma data
     * conservadora do que nenhuma. Item com mais de 7 dias não sai, mesmo que o
     * snapshot como um todo seja mais novo: a lista de fallback reaproveita
     * itens antigos que a fonte alternativa não cobre, e é aqui que eles
     * envelhecem e somem.
     */
    private Optional<List<Indicator>> staleIndicators(String key, Snapshot<Object> snapshot) {
        if (!isServable(key, snapshot) || !(snapshot.payload() instanceof List<?> raw)) {
            return Optional.empty();
        }
        List<Indicator> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (!(element instanceof Indicator indicator)) {
                continue;
            }
            Indicator copy = indicator.staleCopy();
            if (copy.getAsOf() == null) {
                copy.setAsOf(snapshot.savedAt());
            }
            if (!isExpired(copy.getAsOf())) {
                result.add(copy);
            }
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(result));
    }

    private boolean isServable(String key, Snapshot<?> snapshot) {
        if (isExpired(snapshot.savedAt())) {
            memory.invalidate(key);
            return false;
        }
        return true;
    }

    private boolean isExpired(Instant savedAt) {
        return savedAt == null || Duration.between(savedAt, clock.instant()).compareTo(MAX_AGE) > 0;
    }

    private static Indicator newest(Indicator current, Indicator candidate) {
        if (candidate.getAsOf() == null) {
            return current;
        }
        if (current.getAsOf() == null || candidate.getAsOf().isAfter(current.getAsOf())) {
            return candidate;
        }
        return current;
    }

    private static String firstSource(List<Indicator> indicators) {
        if (indicators == null) {
            return null;
        }
        return indicators.stream()
                .map(Indicator::getSource)
                .filter(source -> source != null && !source.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String truncate(String source) {
        if (source == null) {
            return null;
        }
        return source.length() <= SOURCE_MAX_LENGTH ? source : source.substring(0, SOURCE_MAX_LENGTH);
    }
}
