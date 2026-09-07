package br.com.economize.service.connector.pluggy;

import br.com.economize.service.connector.NoOpOpenFinanceProvider;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O contexto padrão dos testes sobe com PLUGGY_ENABLED=false, então nenhum bean
 * do conector é criado e a fiação dele nunca era exercitada — um qualifier
 * errado só apareceria no deploy, com a aplicação recusando-se a subir. Aqui a
 * flag entra ligada, de propósito, para que o contexto inteiro seja montado.
 */
// O application.properties de teste SUBSTITUI o principal, então as
// propriedades do conector precisam ser declaradas aqui inteiras — inclusive as
// que não têm default no @Value (base-url, item-ids).
@SpringBootTest(properties = {
        "economize.pluggy.enabled=true",
        "economize.pluggy.base-url=https://example.test/pluggy",
        "economize.pluggy.client-id=test-client",
        "economize.pluggy.client-secret=test-secret",
        "economize.pluggy.item-ids=",
        "economize.pluggy.owner-email="
})
class PluggyConnectorWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("com a flag ligada o contexto sobe e o PluggyClient recebe o WebClient dedicado")
    void contextLoadsWithConnectorEnabled() {
        // se o @Qualifier("pluggyWebClient") não casasse com bean nenhum, o
        // contexto teria falhado antes de chegar aqui
        assertThat(context.getBean(PluggyClient.class)).isNotNull();
        assertThat(context.getBean(PluggyItemService.class)).isNotNull();
        assertThat(context.getBean(PluggySyncService.class)).isNotNull();

        // o cliente dedicado existe de verdade: os 256 KB do codec padrão
        // estouram numa página de 500 transações de cartão
        assertThat(context.containsBean("pluggyWebClient")).isTrue();
    }

    @Test
    @DisplayName("com a flag ligada o Pluggy é o OpenFinanceProvider da porta neutra, e o vazio não sobe")
    void pluggyIsTheProviderWhenEnabled() {
        // o @ConditionalOnMissingBean da implementação vazia precisa ENXERGAR
        // esta aqui: se a ordem de registro o traísse, haveria dois provedores
        // e a injeção nos controllers falharia por ambiguidade
        assertThat(context.getBeansOfType(OpenFinanceProvider.class)).hasSize(1);
        assertThat(context.getBean(OpenFinanceProvider.class)).isInstanceOf(PluggyOpenFinanceProvider.class);
        assertThat(context.getBeansOfType(NoOpOpenFinanceProvider.class)).isEmpty();
    }
}
