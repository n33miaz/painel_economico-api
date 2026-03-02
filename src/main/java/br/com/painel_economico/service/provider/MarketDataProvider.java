package br.com.painel_economico.service.provider;

import br.com.painel_economico.dto.Indicator;
import reactor.core.publisher.Mono;
import java.util.List;

public interface MarketDataProvider {
    // Retorna o nome do provedor
    String getProviderName();

    // Busca os indicadores
    Mono<List<Indicator>> fetchDefaultIndicators();

    // Busca um ativo específico
    Mono<List<Indicator>> searchIndicator(String query);
}