package br.com.economize.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaActivateRequest(
        @NotBlank(message = "Informe o código do aplicativo autenticador")
        @Pattern(regexp = "\\d{6}", message = "O código tem 6 dígitos")
        String code) {
}
