package br.com.economize.dto.auth;

import br.com.economize.model.TrustedDevice;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um aparelho conhecido, como a tela de Segurança o mostra. Nunca carrega o
 * segredo nem o hash dele — só o que ajuda a pessoa a reconhecer o que está na
 * lista e a decidir o que esquecer.
 */
public record TrustedDeviceResponse(
        UUID id,
        String label,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime expiresAt) {

    public static TrustedDeviceResponse from(TrustedDevice device) {
        return new TrustedDeviceResponse(
                device.getId(),
                device.getLabel(),
                device.getCreatedAt(),
                device.getLastUsedAt(),
                device.getExpiresAt());
    }
}
