package br.com.economize.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Desligar o fator exige a SENHA, e não um código do autenticador. O motivo é o
 * cenário que o fator existe para cobrir: quem está com o celular desbloqueado
 * na mão tem o autenticador ali, e conseguiria desarmar a proteção sem saber
 * nada. A senha é o que essa pessoa não tem.
 */
public record MfaDisableRequest(
        @NotBlank(message = "Informe sua senha para desligar o segundo fator")
        String password) {
}
