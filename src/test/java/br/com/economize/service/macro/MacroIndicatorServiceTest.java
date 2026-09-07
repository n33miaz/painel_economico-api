package br.com.economize.service.macro;

import br.com.economize.dto.indicator.MacroIndicator;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.support.MutableClock;
import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroIndicatorServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final TypeReference<List<MacroIndicator>> TYPE = new TypeReference<>() {
    };

    private final List<String> urls = new ArrayList<>();
    private final Set<Integer> failingSeries = new HashSet<>();
    private MarketSnapshotStore snapshotStore;
    private MacroIndicatorService service;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        br.com.economize.config.MarketSourcesProperties properties = new br.com.economize.config.MarketSourcesProperties();
        properties.setBcbSgsUrl("https://example.test/sgs");
        BcbSgsClient sgs = new BcbSgsClient(StubWebClient.respondingWith(urls, this::respond), properties);
        service = new MacroIndicatorService(sgs, snapshotStore, new MutableClock(NOW));
    }

    private ClientResponse respond(ClientRequest request) {
        String path = request.url().getPath();
        int series = Integer.parseInt(path.replaceAll(".*bcdata\\.sgs\\.(\\d+).*", "$1"));
        if (failingSeries.contains(series)) {
            return StubWebClient.status(HttpStatus.SERVICE_UNAVAILABLE, "");
        }
        return StubWebClient.json(switch (series) {
            case 4389 -> "[{\"data\":\"02/09/2026\",\"valor\":\"13.90\"},{\"data\":\"03/09/2026\",\"valor\":\"13.90\"}]";
            // a meta Selic vem preenchida para a frente: o valor certo é o do último dia até hoje
            case 432 -> "[{\"data\":\"04/09/2026\",\"valor\":\"14.00\"},{\"data\":\"14/09/2026\",\"valor\":\"14.25\"}]";
            case 433 -> "[{\"data\":\"01/07/2026\",\"valor\":\"0.07\"}]";
            case 13522 -> "[{\"data\":\"01/07/2026\",\"valor\":\"4.44\"}]";
            case 1 -> "[{\"data\":\"04/09/2026\",\"valor\":\"5.1253\"}]";
            case 195 -> "[{\"data\":\"03/09/2026\",\"dataFim\":\"03/10/2026\",\"valor\":\"0.6698\"}]";
            case 189 -> "[{\"data\":\"01/08/2026\",\"valor\":\"-0.22\"}]";
            default -> "[]";
        });
    }

    @Test
    @DisplayName("Sete séries em paralelo, na ordem fixa, com unidade, data de referência e fonte")
    void shouldAssembleAllSeries() {
        StepVerifier.create(service.getMacroIndicators())
                .assertNext(indicators -> {
                    assertEquals(List.of("CDI", "SELIC", "IPCA_MES", "IPCA_12M", "USD_PTAX", "POUPANCA", "IGPM"),
                            indicators.stream().map(MacroIndicator::code).toList());

                    MacroIndicator cdi = indicators.get(0);
                    assertEquals(new BigDecimal("13.90"), cdi.value());
                    assertEquals("% a.a.", cdi.unit());
                    assertEquals(LocalDate.of(2026, 9, 3), cdi.referenceDate());
                    assertEquals("Banco Central (SGS 4389)", cdi.source());
                    assertEquals(NOW, cdi.asOf());
                    assertFalse(cdi.stale());

                    MacroIndicator selic = indicators.get(1);
                    assertEquals(new BigDecimal("14.00"), selic.value(), "não pode ser a meta futura");
                    assertEquals(LocalDate.of(2026, 9, 4), selic.referenceDate());

                    assertEquals("BRL", indicators.get(4).unit());
                    assertEquals(new BigDecimal("0.6698"), indicators.get(5).value());
                })
                .verifyComplete();

        assertEquals(7, urls.size());
        assertTrue(urls.stream().allMatch(url -> url.endsWith("/dados/ultimos/20?formato=json")));
        assertEquals(7, snapshotStore.findPayload("data:macro", TYPE).orElseThrow().payload().size());
    }

    @Test
    @DisplayName("Uma série fora não derruba as demais: vem do snapshot marcada stale, ou some")
    void oneFailingSeriesShouldNotBreakTheOthers() {
        MacroIndicator oldIgpm = new MacroIndicator("IGPM", "IGP-M do mês", new BigDecimal("-1.16"), "% a.m.",
                LocalDate.of(2026, 7, 1), "Banco Central (SGS 189)", NOW.minusSeconds(3600), false);
        snapshotStore.savePayload("data:macro", List.of(oldIgpm), "Banco Central (SGS)");
        failingSeries.add(189);
        failingSeries.add(433);

        StepVerifier.create(service.getMacroIndicators())
                .assertNext(indicators -> {
                    assertEquals(6, indicators.size(), "IPCA_MES nunca teve snapshot: não aparece");
                    MacroIndicator igpm = indicators.get(indicators.size() - 1);
                    assertEquals("IGPM", igpm.code());
                    assertTrue(igpm.stale());
                    assertEquals(new BigDecimal("-1.16"), igpm.value());
                    assertEquals(NOW.minusSeconds(3600), igpm.asOf(), "asOf é o original, não o de agora");
                    assertTrue(indicators.subList(0, 5).stream().noneMatch(MacroIndicator::stale));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("SGS inteiro fora: tudo do snapshot (stale); sem snapshot, lista vazia")
    void sgsDownShouldServeSnapshotThenEmpty() {
        for (MacroIndicatorService.Series series : MacroIndicatorService.SERIES) {
            failingSeries.add(series.sgsId());
        }

        StepVerifier.create(service.getMacroIndicators())
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();

        snapshotStore.savePayload("data:macro", List.of(new MacroIndicator("CDI", "CDI", new BigDecimal("13.90"),
                "% a.a.", LocalDate.of(2026, 9, 3), "Banco Central (SGS 4389)", NOW, false)), "Banco Central (SGS)");

        StepVerifier.create(service.getMacroIndicators())
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertTrue(indicators.get(0).stale());
                })
                .verifyComplete();
    }
}
