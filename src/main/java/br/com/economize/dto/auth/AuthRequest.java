package br.com.economize.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * As credenciais do login.
 *
 * <p>Os dois campos de aparelho sao OPCIONAIS e nao existiam no APK publicado:
 * ausentes, o comportamento e exatamente o de antes.
 */
@Data
public class AuthRequest {
    private String email;
    private String password;

    /**
     * Segredo de aparelho conhecido, emitido num segundo passo anterior. Se
     * conferir, o segundo fator nao e pedido — e o que torna o fator
     * suportavel no celular de todo dia.
     */
    @Schema(description = "Segredo do aparelho, se ele ja foi lembrado")
    private String deviceToken;

    /** "iPhone de Alice". So rotulo, para a pessoa reconhecer a lista. */
    @Schema(description = "Como este aparelho aparece na lista de conhecidos")
    private String deviceLabel;
}
