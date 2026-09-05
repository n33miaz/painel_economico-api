package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Uma fala anterior da conversa, reenviada pelo app.
 *
 * <p><b>Por que o app manda o histórico, e não o servidor guarda.</b> O
 * histórico já vive no aparelho (o `aiStore` persiste as últimas 50 mensagens)
 * e é o único lugar em que ele precisa existir: guardá-lo também no servidor
 * criaria uma segunda cópia de conversas sobre a vida financeira de alguém,
 * com ciclo de vida próprio para apagar, migrar e vazar. "Limpar histórico" no
 * app passa a significar o que diz — some, e o servidor nunca teve nada.
 *
 * <p>O preço é que o cliente pode mentir sobre o que foi dito antes. Isso não
 * abre nada: o histórico só volta como CONTEXTO da mesma conversa, e todo dado
 * financeiro do prompt é montado no servidor a partir do banco, a partir do
 * usuário autenticado.
 */
public record ChatTurn(
        @Schema(description = "Quem falou", allowableValues = { "user", "assistant" })
        @NotBlank
        @Pattern(regexp = "user|assistant", message = "O papel deve ser user ou assistant")
        String role,

        @NotBlank
        @Size(max = 2000, message = "Cada fala anterior não pode passar de 2000 caracteres")
        String content) {
}
