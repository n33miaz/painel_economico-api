package br.com.economize.repository;

import br.com.economize.model.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A tabela da V25 contra banco: a coluna se chama {@code key}, palavra
 * reservada no H2 — se a aspa do mapeamento sumir, é aqui que aparece.
 */
@DataJpaTest
@DisplayName("Snapshot de mercado persistido (V25)")
class MarketSnapshotRepositoryTest {

    @Autowired
    private MarketSnapshotRepository repository;

    @Test
    @DisplayName("Grava por chave e a segunda gravação da mesma chave atualiza em vez de duplicar")
    void shouldUpsertByKey() {
        OffsetDateTime savedAt = OffsetDateTime.of(2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC);
        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[{\"code\":\"USD\"}]", savedAt, "AwesomeAPI"));

        MarketSnapshot found = repository.findById("awesome:all").orElseThrow();
        assertEquals("[{\"code\":\"USD\"}]", found.getPayload());
        assertEquals(savedAt.toInstant(), found.getSavedAt().toInstant());
        assertEquals("AwesomeAPI", found.getSource());

        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[]", savedAt.plusHours(1), "Frankfurter (BCE)"));

        assertEquals(1, repository.count(), "chave é chave primária: nunca duas linhas");
        MarketSnapshot updated = repository.findById("awesome:all").orElseThrow();
        assertEquals("[]", updated.getPayload());
        assertEquals("Frankfurter (BCE)", updated.getSource());
    }

    @Test
    @DisplayName("O payload é TEXT: um /all inteiro (dezenas de KB) cabe sem truncar")
    void payloadShouldHoldLargeJson() {
        String big = "[" + "{\"code\":\"USD\",\"buy\":5.1249}".repeat(5000) + "]";
        repository.saveAndFlush(new MarketSnapshot("data:treasury", big,
                OffsetDateTime.now(ZoneOffset.UTC), "Tesouro Transparente"));

        assertEquals(big.length(), repository.findById("data:treasury").orElseThrow().getPayload().length());
    }
}
