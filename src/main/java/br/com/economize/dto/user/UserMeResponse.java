package br.com.economize.dto.user;

import br.com.economize.model.Plan;
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
        boolean mustChangePassword,
        /** Plano gravado na conta (V23); a vigência é adsEnabled, não este campo. */
        Plan plan,
        /** Até quando o PLUS vale; nulo em FREE ou PLUS sem prazo. */
        OffsetDateTime planUntil,
        /**
         * O app mostra anúncios? Decidido AQUI, no servidor: é o oposto de
         * "PLUS vigente", e não pode ser uma marca local que se apaga.
         */
        boolean adsEnabled
) {
    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.isMustChangePassword(),
                user.getPlan(),
                user.getPlanUntil(),
                !user.isPlus());
    }
}
