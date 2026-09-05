package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A pergunta enviada ao assistente.
 *
 * <p>Morava dentro do arquivo do controller, como classe package-private: não
 * aparecia na documentação e não passava por validação nenhuma.
 */
public record ChatRequest(
        /*
         * O teto do app é 500 caracteres (AiAssistant.tsx). Aqui o limite é
         * mais folgado de propósito — ele existe para conter cliente que não é
         * o app, já que cada pergunta vira consumo pago no provedor de IA.
         */
        @Schema(description = "Pergunta do usuário", example = "Quanto gastei com mercado neste mês?")
        @NotBlank(message = "A mensagem não pode estar vazia")
        @Size(max = 2000, message = "A mensagem não pode passar de 2000 caracteres")
        String message,

        /*
         * As falas anteriores, da mais antiga para a mais recente. Ausente ou
         * vazia é o comportamento de antes — e é o que o APK publicado manda.
         *
         * Sem isto o assistente não tinha memória NENHUMA: "e no mês passado?"
         * chegava ao provedor como uma primeira pergunta solta, e a resposta
         * era necessariamente sobre nada. O teto de 12 falas existe porque cada
         * uma vira token pago, e porque o dado financeiro do prompt (montado no
         * servidor) é sempre o atual — conversa antiga demais só ancora o
         * modelo em números que já mudaram.
         */
        @Schema(description = "Falas anteriores da mesma conversa, da mais antiga para a mais recente")
        @Size(max = 12, message = "O histórico não pode passar de 12 falas")
        @Valid
        List<ChatTurn> history
) {
    /** Construtor do caminho antigo: pergunta solta, sem histórico. */
    public ChatRequest(String message) {
        this(message, List.of());
    }

    /** Nunca nulo — quem consome itera sem checar. */
    public List<ChatTurn> history() {
        return history == null ? List.of() : history;
    }
}
