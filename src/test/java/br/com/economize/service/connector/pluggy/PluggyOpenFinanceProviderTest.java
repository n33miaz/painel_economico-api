package br.com.economize.service.connector.pluggy;

import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O adaptador não tem regra própria: traduz nomes e delega. O que se prova
 * aqui é a tradução — em especial que nada do Pluggy vaza para o app além do
 * itemId opaco.
 */
@ExtendWith(MockitoExtension.class)
class PluggyOpenFinanceProviderTest {

    private static final String EMAIL = "teste@economize.app";
    private static final String SCRIPT = "https://cdn.pluggy.ai/pluggy-connect/v2.8.2/pluggy-connect.js";

    @Mock
    private PluggyItemService itemService;

    @Mock
    private PluggySyncService syncService;

    private PluggyOpenFinanceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PluggyOpenFinanceProvider(itemService, syncService, SCRIPT);
    }

    @Test
    @DisplayName("Identidade: id técnico 'pluggy', nome exibível neutro, sempre habilitado")
    void identidade() {
        assertThat(provider.id()).isEqualTo("pluggy");
        assertThat(provider.displayName()).isEqualTo("Open Finance").doesNotContainIgnoringCase("pluggy");
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    @DisplayName("O widget aponta para o script configurado e se identifica como pluggy-connect")
    void widget() {
        OpenFinanceProvider.WidgetDescriptor widget = provider.widget();

        assertThat(widget.scriptUrl()).isEqualTo(SCRIPT);
        assertThat(widget.kind()).isEqualTo("pluggy-connect");
    }

    @Test
    @DisplayName("status tipa o mapa legado: configured e itemCount; o campo owner fica para trás")
    void statusTipaOMapaLegado() {
        when(syncService.status(EMAIL)).thenReturn(Map.of(
                "enabled", true, "owner", true, "configured", true, "itemCount", 3L));

        OpenFinanceProvider.ProviderStatus status = provider.status(EMAIL);

        assertThat(status.enabled()).isTrue();
        assertThat(status.configured()).isTrue();
        assertThat(status.itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("status sem itemCount no mapa responde zero, e configured ausente é falso")
    void statusTolerante() {
        when(syncService.status(EMAIL)).thenReturn(Map.of("enabled", true));

        OpenFinanceProvider.ProviderStatus status = provider.status(EMAIL);

        assertThat(status.configured()).isFalse();
        assertThat(status.itemCount()).isZero();
    }

    @Test
    @DisplayName("connectToken e unlinkItem delegam sem mexer nos argumentos")
    void delegacoesDiretas() {
        when(itemService.connectToken(EMAIL, "item-1")).thenReturn("tok");
        UUID id = UUID.randomUUID();

        assertThat(provider.connectToken(EMAIL, "item-1")).isEqualTo("tok");
        provider.unlinkItem(EMAIL, id);

        verify(itemService).unlink(EMAIL, id);
    }

    @Test
    @DisplayName("registerItem e listItems traduzem para a resposta neutra com institution = connectorName")
    void traducaoDeItens() {
        UUID id = UUID.randomUUID();
        OffsetDateTime criado = OffsetDateTime.parse("2026-08-15T12:00:00Z");
        OffsetDateTime sync = OffsetDateTime.parse("2026-08-16T12:00:00Z");
        PluggyItemResponse item = new PluggyItemResponse(id, "item-1", 612L, "Nubank", criado, sync);
        when(itemService.register(EMAIL, "item-1")).thenReturn(item);
        when(itemService.list(EMAIL)).thenReturn(List.of(item));

        ConnectionResponse registrado = provider.registerItem(EMAIL, "item-1");
        List<ConnectionResponse> lista = provider.listItems(EMAIL);

        assertThat(registrado).isEqualTo(new ConnectionResponse(id, "item-1", 612L, "Nubank", "Nubank", criado, sync));
        assertThat(lista).containsExactly(registrado);
    }

    @Test
    @DisplayName("sync embrulha o resultado do serviço no tipo da porta")
    void syncTraduzResultado() {
        UUID uploadId = UUID.randomUUID();
        BankStatementService.ImportResult result =
                new BankStatementService.ImportResult(uploadId, 12, 7, 2, 3, false, "PLUGGY");
        when(syncService.sync(EMAIL, 90)).thenReturn(new PluggySyncService.SyncResult(result, 2));

        OpenFinanceProvider.SyncResult sync = provider.sync(EMAIL, 90);

        assertThat(sync.result()).isSameAs(result);
        assertThat(sync.itemsSynced()).isEqualTo(2);
    }
}
