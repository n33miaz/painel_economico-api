package br.com.economize.service.connector;

import br.com.economize.service.connector.pluggy.PluggyClient;
import br.com.economize.service.connector.pluggy.PluggyOpenFinanceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O ponto de troca: {@code economize.connector.provider} apontando para um id
 * que nenhuma implementação reconhece deixa a instalação SEM conector mesmo com
 * o Pluggy ligado — os serviços dele existem, mas não são o provedor da porta.
 * É o comportamento que uma segunda implementação vai herdar ao se pendurar no
 * próprio id.
 */
@SpringBootTest(properties = {
        "economize.pluggy.enabled=true",
        "economize.pluggy.base-url=https://example.test/pluggy",
        "economize.pluggy.client-id=test-client",
        "economize.pluggy.client-secret=test-secret",
        "economize.pluggy.item-ids=",
        "economize.pluggy.owner-email=",
        "economize.connector.provider=outro-agregador"
})
class ConnectorProviderSwitchWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Provedor configurado desconhecido: Pluggy ligado mas fora da porta, e o vazio responde")
    void provedorDesconhecidoDeixaOVazioNaPorta() {
        // o conector em si subiu — a flag dele está ligada
        assertThat(context.getBeansOfType(PluggyClient.class)).hasSize(1);
        // mas a porta neutra não o escolheu
        assertThat(context.getBeansOfType(PluggyOpenFinanceProvider.class)).isEmpty();
        assertThat(context.getBean(OpenFinanceProvider.class)).isInstanceOf(NoOpOpenFinanceProvider.class);
    }
}
