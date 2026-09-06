package br.com.economize.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Segundo passo do login: o desafio emitido no primeiro passo, mais o codigo —
 * do autenticador (6 digitos) ou um dos de recuperacao.
 *
 * @param rememberDevice lembrar este aparelho, para os proximos logins dele
 *                       nao pedirem codigo. A resposta traz o segredo em
 *                       deviceToken, e ele e a UNICA vez que ele aparece.
 */
public record MfaChallengeRequest(
        @NotBlank(message = "Desafio ausente — refaça o login")
        String mfaToken,
        @NotBlank(message = "Informe o código de verificação")
        String code,
        boolean rememberDevice,
        String deviceLabel) {

    /** Construtor do caminho antigo: sem lembrar o aparelho. */
    public MfaChallengeRequest(String mfaToken, String code) {
        this(mfaToken, code, false, null);
    }
}
