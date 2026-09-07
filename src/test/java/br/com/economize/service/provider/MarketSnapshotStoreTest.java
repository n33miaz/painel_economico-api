package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import br.com.economize.dto.indicator.MacroIndicator;
import br.com.economize.model.MarketSnapshot;
import br.com.economize.repository.MarketSnapshotRepository;
import br.com.economize.support.MutableClock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As duas camadas do snapshot com o repositório dublado: o banco nunca falha
 * uma requisição, o boot lê do banco, e a idade decide o que ainda sai.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketSnapshotStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final TypeReference<List<MacroIndicator>> MACRO_LIST = new TypeReference<>() {
    };

    @Mock
    private MarketSnapshotRepository repository;

    private final ObjectMapper mapper = MarketSnapshotStore.defaultMapper();
    private MutableClock clock;
    private MarketSnapshotStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        // gravação síncrona no teste: o que em produção vai ao boundedElastic
        // acontece aqui na mesma thread, e o erro aparece (ou não) na hora
        store = new MarketSnapshotStore(repository, mapper, clock, Schedulers.immediate());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of());
    }

    private Indicator usd(String price, Instant asOf, String source) {
        Indicator indicator = new Indicator();
        indicator.setId("currency_USD");
        indicator.setType("currency");
        indicator.setCode("USD");
        indicator.setCodeIn("BRL");
        indicator.setName("Dólar Americano/Real Brasileiro");
        indicator.setBuy(new BigDecimal(price));
        indicator.setAsOf(asOf);
        indicator.setSource(source);
        return indicator;
    }

    private MarketSnapshot row(String key, Object payload, Instant savedAt, String source) throws Exception {
        return new MarketSnapshot(key, mapper.writeValueAsString(payload),
                OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC), source);
    }

    // ------------------------------------------------------------ gravação

    @Test
    @DisplayName("Salvar grava em memória e no banco, com a chave, a fonte e a data")
    void saveShouldWriteThrough() {
        store.save("awesome:all", List.of(usd("5.40", NOW.minusSeconds(60), "AwesomeAPI")), "AwesomeAPI");

        ArgumentCaptor<MarketSnapshot> saved = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(repository).save(saved.capture());
        assertEquals("awesome:all", saved.getValue().getKey());
        assertEquals("AwesomeAPI", saved.getValue().getSource());
        assertEquals(NOW, saved.getValue().getSavedAt().toInstant());
        String json = saved.getValue().getPayload();
        assertTrue(json.contains("\"code\":\"USD\""));
        assertTrue(json.contains("\"asOf\":\"2026-09-06T11:59:00Z\""), "data em ISO, não em época: " + json);
        assertFalse(json.contains("providerTimestamp"), "campo só de entrada não pode ir ao banco");
        assertFalse(json.contains("\"stale\""), "stale é marca de leitura, não de gravação");

        Optional<List<Indicator>> found = store.find("awesome:all");
        assertTrue(found.isPresent());
        assertTrue(found.get().get(0).isStale());
        assertEquals("AwesomeAPI", found.get().get(0).getSource());
    }

    @Test
    @DisplayName("Banco fora do ar na gravação não propaga: a memória segue servindo")
    void databaseFailureOnSaveMustNotPropagate() {
        when(repository.save(any())).thenThrow(new RuntimeException("connection refused"));

        store.save("awesome:all", List.of(usd("5.40", NOW, "AwesomeAPI")), "AwesomeAPI");

        assertTrue(store.find("awesome:all").isPresent(), "a memória não depende do banco");
    }

    @Test
    @DisplayName("Fonte maior que a coluna é cortada em vez de derrubar o INSERT")
    void sourceShouldBeTruncatedToColumnWidth() {
        String longSource = "Frankfurter (BCE)+CoinGecko+BCB PTAX+Brapi+Yahoo Finance";
        store.save("awesome:all", List.of(usd("5.40", NOW, longSource)), longSource);

        ArgumentCaptor<MarketSnapshot> saved = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(repository).save(saved.capture());
        assertEquals(40, saved.getValue().getSource().length());
    }

    // ------------------------------------------------------------- leitura

    @Test
    @DisplayName("Memória vazia (boot) lê do banco e serve stale com a data da gravação")
    void emptyMemoryShouldReadFromDatabase() throws Exception {
        Instant savedAt = NOW.minus(Duration.ofHours(30));
        Indicator legacy = usd("5.33", null, null); // gravado antes de asOf existir
        when(repository.findById("awesome:all"))
                .thenReturn(Optional.of(row("awesome:all", List.of(legacy), savedAt, "AwesomeAPI")));

        Optional<List<Indicator>> found = store.find("awesome:all");

        assertTrue(found.isPresent());
        Indicator usd = found.get().get(0);
        assertTrue(usd.isStale(), "mais de 24h: sai marcado stale");
        assertEquals(savedAt, usd.getAsOf(), "sem asOf próprio, vale a data da gravação");
        assertEquals(new BigDecimal("5.33"), usd.getBuy());

        // segunda leitura vem da memória: o banco não é consultado de novo
        store.find("awesome:all");
        verify(repository, times(1)).findById("awesome:all");
    }

    @Test
    @DisplayName("Snapshot com mais de 7 dias não é servido nem do banco nem da memória")
    void snapshotsOlderThanAWeekMustNotBeServed() throws Exception {
        when(repository.findById("awesome:all")).thenReturn(Optional.of(
                row("awesome:all", List.of(usd("5.00", null, null)), NOW.minus(Duration.ofDays(8)), "AwesomeAPI")));

        assertTrue(store.find("awesome:all").isEmpty());

        // e o que estava na memória envelhece do mesmo jeito
        store.save("brapi:PETR4", List.of(usd("10.00", NOW, "Brapi")), "Brapi");
        clock.advance(Duration.ofDays(8));
        assertTrue(store.find("brapi:PETR4").isEmpty());
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    @DisplayName("Item com asOf de mais de 7 dias some mesmo dentro de um snapshot novo")
    void itemsOlderThanAWeekAreDroppedIndividually() {
        Indicator fresh = usd("5.40", NOW, "Frankfurter (BCE)");
        Indicator ancient = usd("5.90", NOW.minus(Duration.ofDays(9)), "AwesomeAPI");
        ancient.setId("currency_USDT");
        store.save("awesome:all", List.of(fresh, ancient), "Frankfurter (BCE)");

        List<Indicator> found = store.find("awesome:all").orElseThrow();
        assertEquals(1, found.size());
        assertEquals("currency_USD", found.get(0).getId());
    }

    @Test
    @DisplayName("Miss no banco é lembrado: banco fora do ar não é consultado a cada falha de provedor")
    void databaseMissShouldBeRemembered() {
        assertTrue(store.find("brapi:VALE3").isEmpty());
        assertTrue(store.find("brapi:VALE3").isEmpty());

        verify(repository, times(1)).findById("brapi:VALE3");
    }

    @Test
    @DisplayName("Banco fora do ar na leitura devolve vazio, sem exceção")
    void databaseFailureOnReadMustNotPropagate() {
        when(repository.findById(any())).thenThrow(new RuntimeException("connection refused"));

        assertTrue(store.find("awesome:all").isEmpty());
        StepVerifier.create(store.lookup("awesome:all")).verifyComplete();
    }

    @Test
    @DisplayName("findAll no boot povoa a memória com as listas do banco e agrupa por id")
    void findAllShouldHydrateFromDatabase() throws Exception {
        Indicator older = usd("5.20", NOW.minus(Duration.ofDays(2)), "AwesomeAPI");
        Indicator newer = usd("5.30", NOW.minus(Duration.ofHours(1)), "Frankfurter (BCE)");
        Indicator petr = usd("38.00", NOW.minus(Duration.ofHours(1)), "Brapi");
        petr.setId("stock_PETR4");
        when(repository.findAll()).thenReturn(List.of(
                row("awesome:all", List.of(older), NOW.minus(Duration.ofDays(2)), "AwesomeAPI"),
                row("fallback:fiat", List.of(newer), NOW.minus(Duration.ofHours(1)), "Frankfurter (BCE)"),
                row("brapi:PETR4", List.of(petr), NOW.minus(Duration.ofHours(1)), "Brapi"),
                row("search:brapi:XPTO3", List.of(petr), NOW, "Brapi"),
                row("data:macro", List.of(), NOW, "Banco Central (SGS)")));

        List<Indicator> all = store.findAll();

        assertEquals(2, all.size(), "dois dólares viram um, busca e payload genérico ficam de fora");
        Indicator usd = all.stream().filter(i -> "currency_USD".equals(i.getId())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("5.30"), usd.getBuy(), "prevalece o asOf mais recente");
        assertTrue(all.stream().allMatch(Indicator::isStale));

        store.findAll();
        verify(repository, times(1)).findAll();
    }

    // -------------------------------------------------- payloads genéricos

    @Test
    @DisplayName("Payload genérico faz ida e volta pelo banco com o tipo informado pelo dono da chave")
    void genericPayloadShouldRoundTripThroughDatabase() throws Exception {
        MacroIndicator cdi = new MacroIndicator("CDI", "CDI", new BigDecimal("13.90"), "% a.a.",
                LocalDate.of(2026, 9, 3), "Banco Central (SGS 4389)", NOW, false);
        store.savePayload("data:macro", List.of(cdi), "Banco Central (SGS)");

        ArgumentCaptor<MarketSnapshot> saved = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(repository).save(saved.capture());

        // outro processo (memória vazia) lendo a mesma linha
        MarketSnapshotStore rebooted = new MarketSnapshotStore(repository, mapper, clock, Schedulers.immediate());
        when(repository.findById("data:macro")).thenReturn(Optional.of(saved.getValue()));

        MarketSnapshotStore.Snapshot<List<MacroIndicator>> snapshot = rebooted.findPayload("data:macro", MACRO_LIST)
                .orElseThrow();
        assertEquals(List.of(cdi), snapshot.payload());
        assertEquals(NOW, snapshot.savedAt());
        assertEquals("Banco Central (SGS)", snapshot.source());

        StepVerifier.create(rebooted.lookupPayload("data:macro", MACRO_LIST))
                .assertNext(found -> assertEquals(List.of(cdi), found.payload()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Payload genérico exige o prefixo que o mantém fora do agregado da Home")
    void genericPayloadRequiresDataPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> store.savePayload("macro", List.of(), "Banco Central (SGS)"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Sem repositório (modo memória) tudo funciona igual, só sem banco")
    void memoryOnlyModeShouldWork() {
        MarketSnapshotStore memoryOnly = new MarketSnapshotStore();
        memoryOnly.save("awesome:all", List.of(usd("5.40", NOW, "AwesomeAPI")));

        assertTrue(memoryOnly.find("awesome:all").isPresent());
        assertEquals(1, memoryOnly.findAll().size());
        StepVerifier.create(memoryOnly.lookup("awesome:all")).expectNextCount(1).verifyComplete();
        StepVerifier.create(memoryOnly.lookup("nada")).verifyComplete();
    }
}
