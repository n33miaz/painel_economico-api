package br.com.economize.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import br.com.economize.dto.ai.ChatTurn;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente mínimo do {@code /chat/completions} — o protocolo que Gemini, OpenAI,
 * Anthropic (camada de compatibilidade) e OpenRouter aceitam. Uma pergunta, uma
 * resposta em texto: sem streaming, sem ferramentas, sem histórico. É tudo o que
 * os dois consumidores de IA do produto usam hoje.
 *
 * <p>Chamada blocante de propósito, como o {@code PluggyClient}: quem chama já
 * está no {@code boundedElastic}.
 *
 * <p><b>Nada que venha do provedor sai daqui sem passar pela redação.</b> Corpo
 * de erro de terceiro é território onde uma chave ecoada apareceria — e o
 * handler global do projeto registra o corpo bruto de
 * {@code WebClientResponseException} em log. Por isso essa exceção é capturada
 * aqui, sempre, e convertida em {@link AiProviderException}.
 */
@Slf4j
@Component
public class OpenAiCompatibleChatClient {

    // teto do corpo de erro que vai para o log, depois de redigido: o suficiente
    // para reconhecer a falha, curto demais para virar despejo de dados
    private static final int ERROR_LOG_LIMIT = 300;

    private final WebClient webClient;

    public OpenAiCompatibleChatClient(@Qualifier("aiWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Envia system + user e devolve o texto da resposta.
     *
     * @throws AiProviderException qualquer falha do provedor, já classificada e
     *                             com mensagem escrita por nós
     */
    public String complete(AiCallTarget target, String systemPrompt, String userPrompt, Duration timeout) {
        return complete(target, systemPrompt, List.of(), userPrompt, timeout);
    }

    public String complete(AiCallTarget target, String systemPrompt, List<ChatTurn> history,
                           String userPrompt, Duration timeout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", target.model());
        // sistema, a conversa ate aqui, e so entao a pergunta nova — a ordem e
        // o contrato do formato de chat de todos os provedores compativeis
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatTurn turn : history) {
            messages.add(Map.of("role", turn.role(), "content", turn.content()));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        payload.put("messages", messages);
        if (target.maxTokens() != null) {
            payload.put("max_tokens", target.maxTokens());
        }

        Map<String, Object> body;
        try {
            body = webClient.post()
                    // URI pronta: o endpoint vem inteiro das properties e não
                    // deve ser reinterpretado por template
                    .uri(URI.create(target.endpoint()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + target.apiKey())
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block(timeout);
        } catch (WebClientResponseException e) {
            throw classify(target, e);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            // sem stack: a cadeia de causas de um erro de transporte pode
            // carregar a requisição inteira, cabeçalho Authorization incluso
            log.warn("Falha de rede ao falar com o provedor de IA {} ({})",
                    target.provider(), e.getClass().getSimpleName());
            throw new AiProviderException(AiProviderException.Reason.NETWORK,
                    "Não foi possível falar com o provedor de IA.");
        }

        String content = extractContent(body);
        if (content == null || content.isBlank()) {
            log.warn("Provedor de IA {} respondeu sem conteúdo utilizável", target.provider());
            throw new AiProviderException(AiProviderException.Reason.PROVIDER,
                    "O provedor de IA respondeu sem conteúdo.");
        }
        return content;
    }

    private AiProviderException classify(AiCallTarget target, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        // A redação é EXATA porque conhecemos o texto em claro da chave; o corte
        // depois dela é a segunda rede, para o caso de o provedor devolver a
        // chave em outro formato (mascarada, fragmentada, urlencoded)
        String redacted = redact(e.getResponseBodyAsString(), target.apiKey());
        log.warn("Provedor de IA {} recusou a chamada: status={} corpo={}",
                target.provider(), status, redacted);

        AiProviderException.Reason reason;
        String message;
        if (status == 401 || status == 403) {
            reason = AiProviderException.Reason.AUTH;
            // "esta chave", e não "a chave cadastrada": o mesmo texto atende o
            // teste de uma chave DIGITADA (que ainda não foi salva) e o uso da
            // que está gravada. Dizer "cadastrada" mandava metade dos usuários
            // procurar problema na chave errada
            message = "O provedor recusou esta chave.";
        } else if (status == 404) {
            reason = AiProviderException.Reason.MODEL;
            message = "O provedor não reconheceu o modelo escolhido.";
        } else if (status == 429) {
            reason = AiProviderException.Reason.RATE_LIMIT;
            message = "O provedor recusou por limite de uso da sua conta.";
        } else if (status == 400 || status == 422) {
            // 400 aqui quase sempre é modelo inválido ou parâmetro que aquele
            // modelo não aceita — é o que o usuário consegue corrigir
            reason = AiProviderException.Reason.MODEL;
            message = "O provedor recusou o pedido para este modelo.";
        } else {
            reason = AiProviderException.Reason.PROVIDER;
            message = "O provedor de IA falhou ao responder.";
        }
        return new AiProviderException(reason, message);
    }

    /** Nunca deixa a chave (nem um pedaço dela) chegar ao log. */
    static String redact(String body, String apiKey) {
        if (body == null || body.isBlank()) return "(vazio)";
        String cleaned = body.replace(apiKey, "***");
        if (apiKey.length() > 8) {
            // provedores costumam ecoar a chave mascarada no meio ("sk-...abcd");
            // o prefixo e o sufixo também somem
            cleaned = cleaned.replace(apiKey.substring(0, 8), "***")
                    .replace(apiKey.substring(apiKey.length() - 4), "***");
        }
        return cleaned.length() <= ERROR_LOG_LIMIT
                ? cleaned
                : cleaned.substring(0, ERROR_LOG_LIMIT) + "…";
    }

    /**
     * {@code choices[0].message.content}. O campo é texto em todos os quatro
     * provedores, mas o protocolo também admite lista de partes — tratar os dois
     * evita uma resposta vazia inexplicável se algum deles mudar de forma.
     */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> body) {
        if (body == null) return null;
        if (!(body.get("choices") instanceof List<?> choices) || choices.isEmpty()) return null;
        if (!(choices.get(0) instanceof Map<?, ?> choice)) return null;
        if (!(choice.get("message") instanceof Map<?, ?> message)) return null;

        Object content = message.get("content");
        if (content instanceof String text) return text;
        if (content instanceof List<?> parts) {
            List<String> texts = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") instanceof String text) {
                    texts.add(text);
                }
            }
            return texts.isEmpty() ? null : String.join("", texts);
        }
        return null;
    }
}
