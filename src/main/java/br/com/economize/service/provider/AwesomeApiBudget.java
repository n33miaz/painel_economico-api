package br.com.economize.service.provider;

import br.com.economize.config.MarketSourcesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Teto diário de chamadas à AwesomeAPI, no mesmo espírito do
 * {@link br.com.economize.service.catalog.QuoteBudget} da Brapi.
 *
 * <p>
 * <b>O que a AwesomeAPI publica</b> (docs.awesomeapi.com.br, llms-full.txt,
 * lido em 06/09/2026): com cadastro e chave, "100 mil requisições mensais
 * gratuitas sem cache"; nos endpoints sequenciais ({@code /:moeda/:quantidade}
 * e {@code /daily}), "limite para requisições não autenticadas 100,
 * autenticadas 1500" — que é o teto de REGISTROS por chamada, não de chamadas.
 * O teto de chamadas anônimas por dia não é publicado em lugar nenhum; o que se
 * vê em produção é a resposta {@code 429 QuotaExceeded}, cedo no dia, e a
 * partir dela tudo bloqueado até a virada.
 *
 * <p>
 * Daí um orçamento conservador e explícito. A conta do consumo previsto:
 *
 * <pre>
 *   /all (Home)   : cache de 30 min → 48 recargas/dia           =  48
 *   /daily        : cache de 1 h por (moeda, janela), sob demanda ≤  72
 *   teto desta classe (economize.market.awesome-daily-budget)    = 120
 * </pre>
 *
 * Estourado o teto, quem pediria à AwesomeAPI pula direto para as fontes
 * alternativas (Frankfurter, PTAX, CoinGecko, SGS) sem abrir conexão: um 429
 * a mais só aprofunda o bloqueio, e a resposta alternativa é igualmente boa
 * para a Home. A virada é por dia UTC porque é assim que o provedor conta.
 *
 * <p>
 * Limitação aceita, igual à do QuoteBudget: contador por instância, em
 * memória. O free tier roda uma instância só.
 */
@Slf4j
@Component
public class AwesomeApiBudget {

    private final int dailyLimit;
    private final Clock clock;

    private LocalDate currentDay;
    private int used;

    @Autowired
    public AwesomeApiBudget(MarketSourcesProperties properties) {
        this(properties.getAwesomeDailyBudget(), Clock.systemUTC());
    }

    AwesomeApiBudget(int dailyLimit, Clock clock) {
        this.dailyLimit = dailyLimit;
        this.clock = clock;
        this.currentDay = today();
    }

    /** Reserva UMA chamada; false quando o dia já gastou o teto. */
    public synchronized boolean tryAcquire() {
        rolloverIfNeeded();
        if (used >= dailyLimit) {
            return false;
        }
        used++;
        if (used == dailyLimit) {
            log.warn("Orçamento diário da AwesomeAPI esgotado ({}/dia); fontes alternativas até a virada UTC",
                    dailyLimit);
        }
        return true;
    }

    public synchronized int remaining() {
        rolloverIfNeeded();
        return Math.max(0, dailyLimit - used);
    }

    public int limit() {
        return dailyLimit;
    }

    private void rolloverIfNeeded() {
        LocalDate today = today();
        if (!today.equals(currentDay)) {
            currentDay = today;
            used = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
