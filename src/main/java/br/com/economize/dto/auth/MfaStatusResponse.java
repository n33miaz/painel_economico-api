package br.com.economize.dto.auth;

import java.time.OffsetDateTime;

/**
 * Estado do segundo fator desta conta. Nunca carrega o segredo — depois do
 * cadastro ele não sai mais do servidor.
 *
 * @param pendingConfirmation cadastro começado e não confirmado: o app sabe que
 *                            precisa pedir o código, e não oferecer "ativar" de novo
 */
public record MfaStatusResponse(
        boolean enabled,
        boolean pendingConfirmation,
        OffsetDateTime confirmedAt,
        long recoveryCodesRemaining) {
}
