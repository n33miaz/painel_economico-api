package br.com.economize.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Segundo passo do login: o desafio emitido no primeiro passo, mais o código —
 * do autenticador (6 dígitos) ou um dos de recuperação.
 */
public record MfaChallengeRequest(
        @NotBlank(message = "Desafio ausente — refaça o login")
        String mfaToken,
        @NotBlank(message = "Informe o código de verificação")
        String code) {
}
