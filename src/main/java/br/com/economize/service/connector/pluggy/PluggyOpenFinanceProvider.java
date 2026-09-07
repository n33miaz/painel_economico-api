package br.com.economize.service.connector.pluggy;

import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O Pluggy visto pela porta neutra {@link OpenFinanceProvider}.
 *
 * <p>Não tem lógica própria de propósito: tudo continua em
 * {@link PluggyItemService} e {@link PluggySyncService}, que já carregam as
 * regras de dono, carimbo de clientUserId e reconciliação. Esta classe só
 * traduz nomes — e é a única que sabe qual script de widget o site deve
 * carregar.
 *
 * <p>Existe quando o conector está ligado ({@code economize.pluggy.enabled}) E
 * é o provedor escolhido em {@code economize.connector.provider}. Hoje ele é
 * o único valor possível; a segunda condição é o ponto onde uma implementação
 * futura se pendura pelo próprio id, sem que as duas subam juntas.
 */
@Component
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
@ConditionalOnExpression("'${economize.connector.provider:pluggy}'.equalsIgnoreCase('pluggy')")
public class PluggyOpenFinanceProvider implements OpenFinanceProvider {

    public static final String ID = "pluggy";
    public static final String DISPLAY_NAME = "Open Finance";
    public static final String WIDGET_KIND = "pluggy-connect";

    private final PluggyItemService itemService;
    private final PluggySyncService syncService;
    private final String widgetScriptUrl;

    public PluggyOpenFinanceProvider(PluggyItemService itemService, PluggySyncService syncService,
                                     @Value("${economize.pluggy.widget-script-url:"
                                             + "https://cdn.pluggy.ai/pluggy-connect/v2.8.2/pluggy-connect.js}")
                                     String widgetScriptUrl) {
        this.itemService = itemService;
        this.syncService = syncService;
        this.widgetScriptUrl = widgetScriptUrl;
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
        return true;
    }

    @Override
    public ProviderStatus status(String email) {
        // o mapa é o contrato do /connectors/pluggy/status publicado; aqui ele
        // vira tipo, e o campo legado "owner" fica para trás
        Map<String, Object> raw = syncService.status(email);
        long itemCount = raw.get("itemCount") instanceof Number number ? number.longValue() : 0;
        return new ProviderStatus(true, Boolean.TRUE.equals(raw.get("configured")), itemCount);
    }

    @Override
    public String connectToken(String email, String itemId) {
        return itemService.connectToken(email, itemId);
    }

    @Override
    public ConnectionResponse registerItem(String email, String itemId) {
        return neutral(itemService.register(email, itemId));
    }

    @Override
    public List<ConnectionResponse> listItems(String email) {
        return itemService.list(email).stream().map(PluggyOpenFinanceProvider::neutral).toList();
    }

    @Override
    public void unlinkItem(String email, UUID id) {
        itemService.unlink(email, id);
    }

    @Override
    public SyncResult sync(String email, int days) {
        PluggySyncService.SyncResult sync = syncService.sync(email, days);
        return new SyncResult(sync.result(), sync.itemsSynced());
    }

    @Override
    public WidgetDescriptor widget() {
        return new WidgetDescriptor(widgetScriptUrl, WIDGET_KIND);
    }

    private static ConnectionResponse neutral(PluggyItemResponse item) {
        return ConnectionResponse.of(item.id(), item.itemId(), item.connectorId(), item.connectorName(),
                item.createdAt(), item.lastSyncedAt());
    }
}
