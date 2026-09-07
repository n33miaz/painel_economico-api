package br.com.economize.service.provider;

import br.com.economize.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwesomeApiBudgetTest {

    @Test
    @DisplayName("Libera até o teto e recusa depois")
    void shouldGrantUpToLimit() {
        AwesomeApiBudget budget = new AwesomeApiBudget(2, new MutableClock(Instant.parse("2026-09-06T12:00:00Z")));

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire(), "a terceira chamada do dia não pode sair");
        assertEquals(0, budget.remaining());
        assertEquals(2, budget.limit());
    }

    @Test
    @DisplayName("Vira com o dia UTC, que é como o provedor conta")
    void shouldResetOnUtcDayChange() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T23:30:00Z"));
        AwesomeApiBudget budget = new AwesomeApiBudget(1, clock);

        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire());

        clock.advance(Duration.ofMinutes(45));

        assertEquals(1, budget.remaining(), "meia-noite UTC devolve o orçamento");
        assertTrue(budget.tryAcquire());
    }
}
