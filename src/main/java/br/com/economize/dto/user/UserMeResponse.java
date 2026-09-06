package br.com.economize.dto.user;

import br.com.economize.model.User;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserMeResponse(
        UUID id,
        String name,
        String email,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt,
        /** Senha provisoria: o app precisa levar direto para a troca (V21). */
        boolean mustChangePassword
) {
    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.isMustChangePassword());
    }
}
