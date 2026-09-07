package br.com.economize.service.connector;

import br.com.economize.exception.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpOpenFinanceProviderTest {

    private final NoOpOpenFinanceProvider provider = new NoOpOpenFinanceProvider("pluggy");

    @Test
    @DisplayName("Sem conector: desabilitado, nome neutro, sem widget, status zerado sem consultar usuário")
    void desabilitado() {
        assertThat(provider.enabled()).isFalse();
        assertThat(provider.id()).isEqualTo("none");
        assertThat(provider.displayName()).isEqualTo("Open Finance");
        assertThat(provider.widget()).isNull();
        assertThat(provider.status("qualquer@economize.app"))
                .isEqualTo(new OpenFinanceProvider.ProviderStatus(false, false, 0));
    }

    @Test
    @DisplayName("Toda operação responde 503 (ServiceUnavailableException) sem citar provedor")
    void operacoesIndisponiveis() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> provider.connectToken("a@b", null)).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> provider.registerItem("a@b", "x")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> provider.listItems("a@b")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> provider.unlinkItem("a@b", id)).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> provider.sync("a@b", 90))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("indisponível")
                .satisfies(e -> assertThat(e.getMessage().toLowerCase()).doesNotContain("pluggy"));
    }
}
