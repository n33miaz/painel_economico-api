package br.com.economize.dto.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro da conexão que o widget acabou de criar: o app devolve o itemId que
 * recebeu no onSuccess. A API confirma no provedor que a conexão existe e
 * pertence a este usuário antes de gravar o vínculo.
 */
public record RegisterConnectionRequest(
        @NotBlank(message = "itemId é obrigatório")
        @Size(max = 64, message = "itemId deve ter no máximo 64 caracteres")
        String itemId
) {
}
