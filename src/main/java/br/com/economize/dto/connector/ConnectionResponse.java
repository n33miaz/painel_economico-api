package br.com.economize.dto.connector;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma conexão bancária do usuário, sem nenhum segredo e sem nome de provedor.
 *
 * <p>{@code connectorName} continua existindo porque o APK 2.2.0 o lê;
 * {@code institution} é o mesmo valor com o nome que o app novo usa — a
 * instituição conectada ("Nubank"), que é o que a pessoa reconhece na lista.
 * {@code itemId} é a referência opaca da conexão no provedor: o app só a
 * devolve, nunca a interpreta.
 */
public record ConnectionResponse(
        UUID id,
        String itemId,
        Long connectorId,
        String connectorName,
        String institution,
        OffsetDateTime createdAt,
        OffsetDateTime lastSyncedAt
) {

    public static ConnectionResponse of(UUID id, String itemId, Long connectorId, String connectorName,
                                        OffsetDateTime createdAt, OffsetDateTime lastSyncedAt) {
        return new ConnectionResponse(id, itemId, connectorId, connectorName, connectorName, createdAt,
                lastSyncedAt);
    }
}
