package br.com.economize.service.connector;

import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * O conector quando não há conector: entra no lugar do provedor real sempre
 * que nenhum outro {@link OpenFinanceProvider} foi criado — conector desligado
 * ({@code PLUGGY_ENABLED=false}) ou {@code CONNECTOR_PROVIDER} apontando para
 * um id que nenhuma implementação reconhece.
 *
 * <p>O status responde {@code enabled=false} e o app esconde a seção; as
 * operações respondem <b>503</b>, e não 400: o pedido do cliente está certo, é
 * esta instalação que não tem como atendê-lo (mesmo raciocínio do cofre sem
 * chave no EC-107). Com isso os controllers não precisam de {@code null} nem de
 * {@code ObjectProvider} — sempre existe um provedor para chamar.
 *
 * <p><b>Por que não é {@code @Component}.</b> A condição "só se não houver outro
 * provedor" vive em {@link br.com.economize.config.ConnectorProviderConfig},
 * num método {@code @Bean}. Posta na própria classe, com o scan de componentes,
 * a condição é avaliada quando a definição desta classe JÁ está no registro —
 * e ela encontra a si mesma como "o outro provedor", nunca subindo. O contexto
 * inteiro morria sem nenhum {@code OpenFinanceProvider}; foi assim que se
 * descobriu.
 */
@Slf4j
public class NoOpOpenFinanceProvider implements OpenFinanceProvider {

    public static final String ID = "none";
    public static final String DISPLAY_NAME = "Open Finance";
    static final String UNAVAILABLE_MESSAGE =
            "Conexão bancária indisponível nesta instalação — o conector Open Finance está desligado";

    public NoOpOpenFinanceProvider(String configuredProvider) {
        // em INFO, e não WARN: desligado é o estado normal de uma instalação
        // sem credenciais de agregador. O id configurado vai junto para que um
        // CONNECTOR_PROVIDER digitado errado se explique no primeiro log
        log.info("Conector Open Finance desligado nesta instalação (provider configurado: {})",
                configuredProvider);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public ProviderStatus status(String email) {
        return new ProviderStatus(false, false, 0);
    }

    @Override
    public String connectToken(String email, String itemId) {
        throw unavailable();
    }

    @Override
    public ConnectionResponse registerItem(String email, String itemId) {
        throw unavailable();
    }

    @Override
    public List<ConnectionResponse> listItems(String email) {
        throw unavailable();
    }

    @Override
    public void unlinkItem(String email, UUID id) {
        throw unavailable();
    }

    @Override
    public SyncResult sync(String email, int days) {
        throw unavailable();
    }

    @Override
    public WidgetDescriptor widget() {
        return null;
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
    }
}
