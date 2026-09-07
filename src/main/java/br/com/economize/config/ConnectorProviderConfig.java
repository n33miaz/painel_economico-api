package br.com.economize.config;

import br.com.economize.service.connector.NoOpOpenFinanceProvider;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Garante que SEMPRE existe um {@link OpenFinanceProvider}: quando nenhuma
 * implementação real subiu (conector desligado, ou {@code CONNECTOR_PROVIDER}
 * sem implementação correspondente), entra a vazia.
 *
 * <p>A condição precisa estar num método {@code @Bean}, e não na classe do
 * provedor vazio: as implementações reais são componentes varridos, cujas
 * definições já estão no registro quando este método é avaliado — a condição
 * as enxerga. Posta na própria classe varrida, ela enxergaria também a si
 * mesma e nunca casaria (ver o comentário em {@link NoOpOpenFinanceProvider}).
 */
@Configuration
public class ConnectorProviderConfig {

    @Bean
    @ConditionalOnMissingBean(OpenFinanceProvider.class)
    public OpenFinanceProvider noOpOpenFinanceProvider(
            @Value("${economize.connector.provider:pluggy}") String configuredProvider) {
        return new NoOpOpenFinanceProvider(configuredProvider);
    }
}
