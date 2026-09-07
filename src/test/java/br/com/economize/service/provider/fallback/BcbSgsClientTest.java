package br.com.economize.service.provider.fallback;

import br.com.economize.support.StubWebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcbSgsClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Lê data dd/MM/yyyy e valor em texto; ponto ilegível é pulado, e dataFim é ignorado")
    void shouldParsePoints() throws Exception {
        String body = """
                [{"data":"03/09/2026","dataFim":"03/10/2026","valor":"0.6698"},
                 {"data":"xx/09/2026","valor":"1"},
                 {"data":"04/09/2026","valor":"5.1253"},
                 {"data":"05/09/2026","valor":""}]
                """;
        List<BcbSgsClient.Point> points = BcbSgsClient.parse(mapper.readTree(body));

        assertEquals(2, points.size());
        assertEquals(LocalDate.of(2026, 9, 3), points.get(0).date());
        assertEquals(new BigDecimal("0.6698"), points.get(0).value());
        assertEquals(new BigDecimal("5.1253"), points.get(1).value());
    }

    @Test
    @DisplayName("Monta a URL do SGS e propaga o 404 (página HTML) como erro para o chamador decidir")
    void shouldRequestSeriesAndPropagateErrors() {
        List<String> urls = new ArrayList<>();
        BcbSgsClient client = new BcbSgsClient(StubWebClient.respondingWith(urls, request -> request.url().getPath().contains("4389")
                ? StubWebClient.json("[{\"data\":\"03/09/2026\",\"valor\":\"13.90\"}]")
                : StubWebClient.status(HttpStatus.NOT_FOUND, "<!doctype html>")), "https://example.test/sgs");

        StepVerifier.create(client.lastValues(4389, 3))
                .assertNext(points -> assertEquals(new BigDecimal("13.90"), points.get(0).value()))
                .verifyComplete();
        StepVerifier.create(client.lastValues(99999, 3))
                .expectError(WebClientResponseException.NotFound.class)
                .verify();

        assertEquals("https://example.test/sgs/bcdata.sgs.4389/dados/ultimos/3?formato=json", urls.get(0));
        assertTrue(urls.get(1).contains("bcdata.sgs.99999"));
    }
}
