package br.com.economize.dto.connector;

import br.com.economize.service.connector.OpenFinanceProvider;

import java.util.UUID;

/**
 * Resultado de uma sincronização — os mesmos campos que o upload de extrato
 * devolve, mais quantas conexões foram percorridas. É o mesmo shape do
 * {@code POST /connectors/pluggy/sync} legado, agora tipado.
 */
public record ConnectorSyncResponse(
        UUID uploadId,
        int transactionsImported,
        int suggested,
        int uncategorized,
        int reconciled,
        String format,
        int itemsSynced
) {

    public static ConnectorSyncResponse from(OpenFinanceProvider.SyncResult sync) {
        var result = sync.result();
        return new ConnectorSyncResponse(result.uploadId(), result.transactionsImported(), result.suggested(),
                result.uncategorized(), result.reconciled(), result.format(), sync.itemsSynced());
    }
}
