package br.com.economize.service.connector;

import br.com.economize.service.connector.pluggy.PluggyOpenFinanceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Com o conector desligado (o padrão dos testes) o provedor que existe é o
 * vazio, e só ele. É o {@code @ConditionalOnMissingBean} da implementação vazia
 * sendo exercitado no contexto real — condição em componente varrido é sensível
 * à ordem de registro, e o único jeito de saber que ela vale é subir o
 * contexto. Mesma anotação do {@code EconomizeApiApplicationTests}, para
 * reaproveitar o contexto em cache.
 */
@SpringBootTest
class ConnectorProviderWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Conector desligado: o único OpenFinanceProvider é o vazio")
    void semConectorEntraOProvedorVazio() {
        assertThat(context.getBeansOfType(OpenFinanceProvider.class)).hasSize(1);
        assertThat(context.getBean(OpenFinanceProvider.class)).isInstanceOf(NoOpOpenFinanceProvider.class);
        assertThat(context.getBeansOfType(PluggyOpenFinanceProvider.class)).isEmpty();
    }
}
