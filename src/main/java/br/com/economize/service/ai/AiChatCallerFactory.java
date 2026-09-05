package br.com.economize.service.ai;

import br.com.economize.dto.ai.ChatTurn;
import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.security.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decide COM QUAL chave e para QUAL provedor cada chamada de IA sai — EC-107.
 *
 * <p>A regra tem três casos e nenhum deles é silencioso:
 * <ol>
 *   <li><b>O usuário cadastrou chave própria</b> → a chamada sai nela, para o
 *   provedor e o modelo que ele escolheu.</li>
 *   <li><b>O usuário não cadastrou nada</b> → cai na chave do servidor, pelo
 *   caminho do Spring AI, <b>exatamente como antes deste ticket</b>. É o mesmo
 *   {@code ChatClient} autoconfigurado de sempre; nada foi reimplementado
 *   embaixo de quem não pediu mudança nenhuma.</li>
 *   <li><b>O usuário cadastrou e a chave não pode ser lida</b> (chave-mestra
 *   trocada ou ausente) → <b>erro honesto</b>. Não cai na chave do servidor: ele
 *   escolheu para onde os dados financeiros dele deveriam ir, e trocar esse
 *   destino em silêncio — mandando o extrato para o provedor do dono do deploy e
 *   gastando o dinheiro dele — seria pior do que falhar dizendo o que houve.</li>
 * </ol>
 */
@Slf4j
@Service
public class AiChatCallerFactory {

    private final UserAiSettingsRepository repository;
    private final SecretCipher cipher;
    private final AiProviderProperties properties;
    private final OpenAiCompatibleChatClient httpClient;
    private final ChatClient serverChatClient;
    private final String serverModel;

    public AiChatCallerFactory(UserAiSettingsRepository repository,
                               SecretCipher cipher,
                               AiProviderProperties properties,
                               OpenAiCompatibleChatClient httpClient,
                               ChatClient.Builder serverChatClientBuilder,
                               @Value("${spring.ai.openai.chat.options.model:}") String serverModel) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.httpClient = httpClient;
        this.serverChatClient = serverChatClientBuilder.build();
        this.serverModel = serverModel;
    }

    /**
     * @param serverFallbackAllowed o chamador aceita rodar na chave do servidor?
     *                              O assistente aceita (é o comportamento de
     *                              hoje); a categorização só aceita quando a
     *                              flag {@code economize.ai.categorization.enabled}
     *                              está ligada, porque ali quem paga é o dono do
     *                              deploy e a decisão é dele
     * @return vazio quando não há IA aplicável para este usuário
     * @throws IllegalArgumentException a configuração existe e está ilegível — o
     *                                  usuário precisa recadastrar (vira 400)
     */
    public Optional<AiChatCaller> resolve(User user, boolean serverFallbackAllowed) {
        // Antes de qualquer consulta: numa instalação sem cofre, nenhuma chave
        // própria é legível, então quando o chamador também não aceita a chave do
        // servidor não existe caminho nenhum e não há o que perguntar ao banco.
        // É o estado da instalação padrão (flag desligada, SECRET_ENCRYPTION_KEY
        // vazia), e é o que devolve a importação ao custo que ela tinha antes de
        // o AiCategorySuggester deixar de ser condicional: zero consulta.
        if (!serverFallbackAllowed && !cipher.isAvailable()) {
            return Optional.empty();
        }
        Optional<UserAiSettings> settings = repository.findByUserId(user.getId());
        if (settings.isEmpty()) {
            return serverFallbackAllowed ? Optional.of(serverCaller()) : Optional.empty();
        }
        return Optional.of(new ByokCaller(targetFor(user, settings.get())));
    }

    /**
     * Monta o destino a partir da linha do banco, decifrando a chave. A chave
     * fica viva apenas dentro do {@link AiCallTarget} que a chamada consome — não
     * há cache de texto em claro em lugar nenhum.
     */
    public AiCallTarget targetFor(User user, UserAiSettings settings) {
        String apiKey;
        try {
            apiKey = cipher.decrypt(settings.getApiKeyCipher(), user.getId().toString());
        } catch (SecretCipher.Unreadable e) {
            // a mensagem do cofre nomeia a chave-mestra e o tipo da falha, nunca
            // conteúdo — pode ir para o log
            log.warn("Chave de IA do usuário não pôde ser decifrada: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Sua chave de IA não pôde ser lida com a configuração atual do servidor. "
                            + "Cadastre a chave novamente nas opções de IA.");
        }
        AiProviderProperties.ProviderConfig config = properties.get(settings.getProvider());
        return new AiCallTarget(settings.getProvider(), settings.getModel(),
                config.endpoint(), apiKey, config.maxTokens());
    }

    private AiChatCaller serverCaller() {
        return new ServerCaller();
    }

    /**
     * O caminho de sempre: {@code ChatClient} autoconfigurado pelo Spring AI em
     * cima de {@code spring.ai.openai.*}. A montagem do prompt é a mesma linha
     * que existia nos dois serviços antes do EC-107.
     */
    private class ServerCaller implements AiChatCaller {

        @Override
        public String complete(String systemPrompt, List<ChatTurn> history, String userPrompt) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            for (ChatTurn turn : history) {
                // "assistant" vira AssistantMessage e "user" vira UserMessage:
                // sem essa distincao o modelo le as proprias respostas como se
                // fossem pedidos do usuario
                messages.add("assistant".equals(turn.role())
                        ? new AssistantMessage(turn.content())
                        : new UserMessage(turn.content()));
            }
            messages.add(new UserMessage(userPrompt));
            return serverChatClient.prompt(new Prompt(messages)).call().content();
        }

        @Override
        public boolean userOwned() {
            return false;
        }

        @Override
        public String describe() {
            return "chave do servidor" + (serverModel.isBlank() ? "" : " / " + serverModel);
        }
    }

    /** A chamada sai na chave do usuário, pelo cliente HTTP compatível. */
    private class ByokCaller implements AiChatCaller {

        private final AiCallTarget target;

        ByokCaller(AiCallTarget target) {
            this.target = target;
        }

        @Override
        public String complete(String systemPrompt, List<ChatTurn> history, String userPrompt) {
            return httpClient.complete(target, systemPrompt, history, userPrompt, properties.getTimeout());
        }

        @Override
        public boolean userOwned() {
            return true;
        }

        @Override
        public String describe() {
            return "chave do usuário / " + target.provider() + " / " + target.model();
        }
    }
}
