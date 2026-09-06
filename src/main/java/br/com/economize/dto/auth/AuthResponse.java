package br.com.economize.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Resposta do login.
 *
 * <p>{@code token} e {@code name} são contrato do APK publicado e ficam como
 * estavam. Os dois campos novos existem para o segundo fator e só aparecem
 * quando ele está no caminho — {@code JsonInclude.NON_NULL} garante que a
 * resposta de quem não usa MFA seja byte a byte a de sempre.
 *
 * <p>Quando {@code mfaRequired} é verdadeiro, {@code token} vem NULO de
 * propósito: nenhuma sessão nasce antes do segundo passo. O que vem é o
 * {@code mfaToken}, um desafio de vida curta que só serve para
 * {@code POST /auth/login/mfa}.
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private String token;
    private String name;
    private Boolean mfaRequired;
    private String mfaToken;

    /**
     * Segredo do aparelho recem-lembrado. So aparece quando o segundo passo
     * pediu para lembrar, e e a UNICA vez que ele existe fora do aparelho.
     */
    private String deviceToken;

    public AuthResponse(String token, String name) {
        this(token, name, null, null, null);
    }

    /** Primeiro passo concluído, segundo pendente. */
    public static AuthResponse challenge(String mfaToken) {
        return new AuthResponse(null, null, true, mfaToken, null);
    }
}
