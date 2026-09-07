package br.com.economize.dto.connector;

import br.com.economize.service.connector.OpenFinanceProvider;

/**
 * Estado do conector para o usuário autenticado. {@code enabled=false} é o
 * sinal para o app ESCONDER a seção de conexão bancária inteira; com
 * {@code enabled=true} e {@code configured=false} ele mostra o botão de
 * conectar; {@code configured=true} libera sincronizar.
 */
public record ConnectorStatusResponse(
        boolean enabled,
        boolean configured,
        long itemCount,
        ProviderInfo provider,
        /** Nulo quando não há provedor nesta instalação. */
        OpenFinanceProvider.WidgetDescriptor widget
) {

    /** Só o que o app pode mostrar: o nome neutro. O id é para diagnóstico. */
    public record ProviderInfo(String id, String displayName) {
    }
}
