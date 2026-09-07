package br.com.economize.service;

import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.dto.Indicator;
import br.com.economize.service.provider.MarketDataProvider;
import br.com.economize.service.provider.MarketSnapshotStore;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class IndicatorService {

        private final List<MarketDataProvider> dataProviders;
        private final MarketSnapshotStore snapshotStore;
        private final HistoricalDataService historicalDataService;

        public IndicatorService(List<MarketDataProvider> dataProviders, MarketSnapshotStore snapshotStore,
                        HistoricalDataService historicalDataService) {
                this.dataProviders = dataProviders;
                this.snapshotStore = snapshotStore;
                this.historicalDataService = historicalDataService;
        }

        @Cacheable("indicators")
        @CircuitBreaker(name = "indicators", fallbackMethod = "getAllIndicatorsFallback")
        public Mono<List<Indicator>> getAllIndicators() {
                log.debug("Iniciando orquestração de dados com {} provedores", dataProviders.size());

                return Flux.fromIterable(dataProviders)
                                .flatMap(MarketDataProvider::fetchDefaultIndicators)
                                .reduce(new java.util.ArrayList<Indicator>(), (acc, list) -> {
                                        acc.addAll(list);
                                        return acc;
                                });
        }

        public Mono<List<Indicator>> getAllIndicatorsFallback(Throwable t) {
                // Prefere o último snapshot bom (stale) a devolver lista vazia
                List<Indicator> stale = snapshotStore.findAll();
                if (!stale.isEmpty()) {
                        log.warn("Circuit Breaker aberto ({}); servindo snapshot stale com {} indicadores",
                                        t.getMessage(), stale.size());
                        return Mono.just(stale);
                }
                log.error("Circuit Breaker aberto e sem snapshot stale. Retornando lista vazia. Erro: {}",
                                t.getMessage());
                return Mono.just(Collections.emptyList());
        }

        // Busca dinâmica. O cache curto (5 min) existe por causa da cota da
        // Brapi: repetir o mesmo termo — usuário digitando, voltando à tela, ou
        // a mesma página do catálogo sendo pedida de novo — não pode custar
        // requisição nova. Quem debita o orçamento é o provedor, então tudo que
        // for atendido daqui sai de graça.
        @Cacheable("indicatorSearch")
        public Mono<List<Indicator>> searchIndicators(String query) {
                log.info("Buscando dinamicamente pelo ativo: {}", query);
                return Flux.fromIterable(dataProviders)
                                .flatMap(provider -> provider.searchIndicator(query))
                                .reduce(new java.util.ArrayList<Indicator>(), (acc, list) -> {
                                        acc.addAll(list);
                                        return acc;
                                });
        }

        /**
         * A série do gráfico mora em {@link HistoricalDataService}: é lá que ficam
         * o orçamento da AwesomeAPI, o fallback (SGS/CoinGecko), o snapshot e o
         * cache de 1h. Este método existe para o contrato de quem já chama aqui.
         */
        public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
                return historicalDataService.getHistoricalData(currencyCode, days);
        }

        public Mono<BigDecimal> calculateConversion(String currencyCode, BigDecimal amountInBrl) {
                return getAllIndicators()
                                .flatMap(indicators -> Mono.justOrEmpty(indicators.stream()
                                                .filter(i -> i.getCode() != null
                                                                && i.getCode().equalsIgnoreCase(currencyCode))
                                                .findFirst()))
                                .switchIfEmpty(Mono.error(
                                                new IllegalArgumentException("Moeda não encontrada: " + currencyCode)))
                                .map(indicator -> {
                                        BigDecimal buyPrice = indicator.getBuy();
                                        if (buyPrice == null || buyPrice.compareTo(BigDecimal.ZERO) == 0) {
                                                throw new IllegalArgumentException(
                                                                "Cotação indisponível para conversão.");
                                        }
                                        return amountInBrl.divide(buyPrice, 2, RoundingMode.HALF_EVEN);
                                });
        }
}
