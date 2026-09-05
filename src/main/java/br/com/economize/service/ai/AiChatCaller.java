package br.com.economize.service.ai;

import br.com.economize.dto.ai.ChatTurn;

import java.util.List;

/**
 * Uma chamada de IA ja resolvida: quem pergunta nao precisa saber se por tras
 * esta a chave do servidor (o caminho de sempre) ou a chave do proprio usuario
 * (EC-107).
 */
public interface AiChatCaller {

    /** Uma pergunta, uma resposta em texto. */
    default String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(), userPrompt);
    }

    /**
     * A mesma coisa, com as falas anteriores da conversa entre o prompt de
     * sistema e a pergunta nova. Historico vazio produz exatamente a chamada
     * de antes, mensagem por mensagem.
     */
    String complete(String systemPrompt, List<ChatTurn> history, String userPrompt);

    /** A chamada sai na chave do usuario? Falso = chave do servidor. */
    boolean userOwned();

    /** Descricao sem segredo, para log e diagnostico. */
    String describe();
}
