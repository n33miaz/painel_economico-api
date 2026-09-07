package br.com.economize.service.macro;

import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.dto.indicator.TreasuryBond;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.support.MutableClock;
import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreasuryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final TypeReference<List<TreasuryBond>> TYPE = new TypeReference<>() {
    };

    /** Cabeçalho e linhas reais do CSV do Tesouro Transparente (06/09/2026). */
    private static final String CSV_HEADER = "Tipo Titulo;Data Vencimento;Data Base;Taxa Compra Manha;"
            + "Taxa Venda Manha;PU Compra Manha;PU Venda Manha;PU Base Manha\n";
    private static final String CSV_TODAY = """
            Tesouro Selic;01/03/2029;04/09/2026;0,03;0,04;19810,47;19795,28;19795,28
            Tesouro Prefixado;01/01/2032;04/09/2026;14,31;14,43;493,41;490,42;490,42
            Tesouro Renda+ Aposentadoria Extra;15/12/2054;04/09/2026;7,35;7,47;1412,47;1387,68;1387,68
            Tesouro IGPM+ com Juros Semestrais;01/01/2031;04/09/2026;6,10;6,22;8000,10;7900,00;7900,00
            """;
    private static final String CSV_YESTERDAY = "Tesouro Selic;01/03/2029;03/09/2026;0,03;0,04;19800,00;19790,00;19790,00\n";

    private static final String OFFICIAL = """
            {"responseStatus":200,"response":{
              "TrsrBondMkt":{"opngDtTm":"2026-09-04T09:25:00","qtnDtTm":"2026-09-04T15:10:00","stsCd":1},
              "TrsrBdTradgList":[
                {"TrsrBd":{"cd":180,"nm":"Tesouro Selic 2029","mtrtyDt":"2029-03-01T00:00:00","minInvstmtAmt":148.62,
                  "untrInvstmtVal":19810.47,"anulInvstmtRate":0.03,"untrRedVal":19795.28,"anulRedRate":0.04,
                  "FinIndxs":{"cd":19,"nm":"SELIC"}}},
                {"TrsrBd":{"nm":"Tesouro Renda+ Aposentadoria Extra 2054","mtrtyDt":"2054-12-15T00:00:00",
                  "untrInvstmtVal":1412.47,"anulInvstmtRate":7.35,"untrRedVal":1387.68,"anulRedRate":7.47,
                  "FinIndxs":{"cd":22,"nm":"IPCA"}}}]}}
            """;

    private final List<String> urls = new ArrayList<>();
    private final List<ClientRequest> requests = new ArrayList<>();
    private boolean officialAlive;
    private boolean csvAlive = true;
    private String csvBody = CSV_HEADER + CSV_TODAY + CSV_YESTERDAY;

    private MarketSnapshotStore snapshotStore;
    private TreasuryService service;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        MarketSourcesProperties properties = new MarketSourcesProperties();
        properties.setTreasuryUrl("https://example.test/tesouro/treasurybondsinfo.json");
        properties.setTreasuryCsvUrl("https://example.test/tesouro/precotaxatesourodireto.csv");
        service = new TreasuryService(StubWebClient.respondingWith(urls, this::respond), properties, snapshotStore,
                new MutableClock(NOW));
    }

    private ClientResponse respond(ClientRequest request) {
        requests.add(request);
        if (request.url().getPath().endsWith(".json")) {
            return officialAlive ? StubWebClient.json(OFFICIAL) : StubWebClient.status(HttpStatus.GONE, "gone");
        }
        return csvAlive ? StubWebClient.text("text/csv", csvBody)
                : StubWebClient.status(HttpStatus.SERVICE_UNAVAILABLE, "");
    }

    @Test
    @DisplayName("JSON oficial em 410: lê o CSV do Tesouro Transparente e devolve só o dia mais recente")
    void goneOfficialSourceShouldFallToCsv() {
        StepVerifier.create(service.getBonds())
                .assertNext(bonds -> {
                    assertEquals(4, bonds.size(), "a linha de 03/09 fica de fora");

                    TreasuryBond selic = bonds.get(0);
                    assertEquals("Tesouro Selic 2029", selic.name());
                    assertEquals(TreasuryBond.SELIC, selic.indexer());
                    assertEquals(LocalDate.of(2029, 3, 1), selic.maturity());
                    assertEquals(new BigDecimal("0.03"), selic.annualRateBuy());
                    assertEquals(new BigDecimal("0.04"), selic.annualRateSell());
                    assertEquals(new BigDecimal("19810.47"), selic.unitPriceBuy());
                    assertEquals(new BigDecimal("19795.28"), selic.unitPriceSell());
                    assertNull(selic.minInvestment(), "o CSV não traz aplicação mínima");
                    assertEquals(TreasuryService.CSV_SOURCE, selic.source());
                    // 09:30 de Brasília do dia-base = 12:30Z
                    assertEquals(Instant.parse("2026-09-04T12:30:00Z"), selic.asOf());
                    assertFalse(selic.stale());

                    assertEquals(TreasuryBond.PREFIXADO, bonds.get(1).indexer());
                    assertEquals(TreasuryBond.IPCA, bonds.get(2).indexer(), "Renda+ é IPCA+");
                    assertEquals(TreasuryBond.OTHER, bonds.get(3).indexer());
                })
                .verifyComplete();

        assertEquals(2, urls.size());
        assertTrue(urls.get(0).endsWith("treasurybondsinfo.json"));
        assertTrue(urls.get(1).endsWith("precotaxatesourodireto.csv"));
        assertTrue(requests.stream().allMatch(request -> request.headers().getFirst(HttpHeaders.USER_AGENT)
                .startsWith("Mozilla/5.0")), "as duas fontes recebem User-Agent de navegador");
        assertEquals(TreasuryService.CSV_SOURCE,
                snapshotStore.findPayload("data:treasury", TYPE).orElseThrow().source());
    }

    @Test
    @DisplayName("CSV de 14 MB: só o começo é lido — o resto da conexão é cancelado")
    void hugeCsvShouldBeReadOnlyAtTheBeginning() {
        StringBuilder huge = new StringBuilder(CSV_HEADER).append(CSV_TODAY);
        for (int i = 0; i < 5000; i++) {
            huge.append(CSV_YESTERDAY);
        }
        csvBody = huge.toString();
        assertTrue(csvBody.length() > TreasuryService.CSV_READ_LIMIT * 4);

        StepVerifier.create(service.getBonds())
                .assertNext(bonds -> assertEquals(4, bonds.size()))
                .verifyComplete();
    }

    @Test
    @DisplayName("JSON oficial vivo: preço intradiário, aplicação mínima e a hora da cotação")
    void officialSourceShouldBePreferred() throws Exception {
        officialAlive = true;

        StepVerifier.create(service.getBonds())
                .assertNext(bonds -> {
                    assertEquals(2, bonds.size());
                    TreasuryBond selic = bonds.get(0);
                    assertEquals(TreasuryService.OFFICIAL_SOURCE, selic.source());
                    assertEquals(new BigDecimal("148.62"), selic.minInvestment());
                    assertEquals(new BigDecimal("19810.47"), selic.unitPriceBuy());
                    // 15:10 de Brasília = 18:10Z
                    assertEquals(Instant.parse("2026-09-04T18:10:00Z"), selic.asOf());
                    assertEquals(TreasuryBond.IPCA, bonds.get(1).indexer());
                })
                .verifyComplete();

        assertEquals(1, urls.size(), "com a oficial viva o CSV não é pedido");

        // e o parse sem a hora da cotação usa o instante da leitura
        List<TreasuryBond> parsed = TreasuryService.parseOfficial(
                new ObjectMapper().readTree("{\"response\":{\"TrsrBdTradgList\":[{\"TrsrBd\":{\"nm\":\"Tesouro Prefixado 2027\",\"mtrtyDt\":\"2027-01-01T00:00:00\"}}]}}"),
                NOW);
        assertEquals(NOW, parsed.get(0).asOf());
        assertEquals(TreasuryBond.PREFIXADO, parsed.get(0).indexer());
        assertNull(parsed.get(0).unitPriceBuy());
    }

    @Test
    @DisplayName("As duas fontes fora: snapshot marcado stale; sem snapshot, lista vazia")
    void bothSourcesDownShouldServeSnapshotThenEmpty() {
        csvAlive = false;

        StepVerifier.create(service.getBonds())
                .assertNext(bonds -> assertTrue(bonds.isEmpty()))
                .verifyComplete();

        snapshotStore.savePayload("data:treasury", List.of(new TreasuryBond("Tesouro Selic 2029", "SELIC",
                LocalDate.of(2029, 3, 1), new BigDecimal("0.03"), new BigDecimal("0.04"), new BigDecimal("19800.00"),
                new BigDecimal("19790.00"), null, NOW.minusSeconds(86400), TreasuryService.CSV_SOURCE, false)),
                TreasuryService.CSV_SOURCE);

        StepVerifier.create(service.getBonds())
                .assertNext(bonds -> {
                    assertEquals(1, bonds.size());
                    assertTrue(bonds.get(0).stale());
                    assertEquals(NOW.minusSeconds(86400), bonds.get(0).asOf(), "a data continua sendo a original");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Linha cortada ou malformada é pulada sem derrubar o dia")
    void malformedLinesShouldBeSkipped() {
        List<TreasuryBond> bonds = TreasuryService.parseCsv(CSV_HEADER
                + "Tesouro Selic;01/03/2029;04/09/2026;0,03;0,04;19810,47;19795,28;19795,28\n"
                + "Tesouro Prefixado;xx/01/2032;04/09/2026;14,31;14,43;493,41;490,42;490,42\n"
                + "Tesouro IPCA+;15/05/2035;04/09/2026;7,3");

        assertEquals(1, bonds.size());
        assertEquals("Tesouro Selic 2029", bonds.get(0).name());
        assertTrue(TreasuryService.parseCsv("").isEmpty());
        assertTrue(TreasuryService.parseCsv(CSV_HEADER).isEmpty());
    }
}
