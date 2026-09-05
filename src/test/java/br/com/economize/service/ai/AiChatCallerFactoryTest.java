package br.com.economize.service.ai;

import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * De qual chave sai cada chamada de IA.
 *
 * <p>O {@link AiProviderProperties} vem INJETADO do contexto, não construído à
 * mão: as asserções sobre endpoint e teto de tokens precisam valer sobre o
 * catálogo que o Spring monta a partir das properties de teste. Construído à
 * mão, este arquivo já afirmou que a Anthropic tinha teto de tokens enquanto o
 * bean real, sobrescrito pelo ambiente, tinha nulo.
 */
@SpringBootTest
class AiChatCallerFactoryTest {

    private static final String KEY_1 = "dGVzdGUtZGUtY2hhdmUtbWVzdHJhLTMyLWJ5dGVzISE=";
    private static final String KEY_2 = "c2VndW5kYS1jaGF2ZS1tZXN0cmEtZGUtMzItYnl0ZXM=";
    private static final String CHAVE_DO_USUARIO = "sk-ant-chave-do-usuario-0987654321";

    @Autowired
    private AiProviderProperties properties;

    private UserAiSettingsRepository repository;
    private OpenAiCompatibleChatClient httpClient;
    private ChatClient serverChatClient;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(UserAiSettingsRepository.class);
        httpClient = mock(OpenAiCompatibleChatClient.class);
        serverChatClient = mock(ChatClient.class);
        user = User.builder().id(UUID.randomUUID()).email("dono@economize.app").build();
    }

    private AiChatCallerFactory factory(SecretCipher cipher) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(serverChatClient);
        return new AiChatCallerFactory(repository, cipher, properties, httpClient, builder, "gemini-2.0-flash");
    }

    private UserAiSettings settingsWith(SecretCipher cipher, AiProvider provider, String model) {
        String envelope = cipher.encrypt(CHAVE_DO_USUARIO, user.getId().toString());
        return UserAiSettings.builder()
                .user(user)
                .provider(provider)
                .model(model)
                .apiKeyCipher(envelope)
                .masterKeyId(SecretCipher.keyIdOf(envelope))
                .apiKeyLast4("4321")
                .build();
    }

    @Test
    @DisplayName("Sem chave própria, a chamada cai na chave do SERVIDOR — o caminho de antes do EC-107")
    void shouldFallBackToServerKey() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        AiChatCallerFactory factory = factory(new SecretCipher(KEY_1, "k1", ""));

        Optional<AiChatCaller> caller = factory.resolve(user, true);

        assertThat(caller).isPresent();
        assertThat(caller.get().userOwned()).isFalse();
        assertThat(caller.get().describe()).contains("servidor").contains("gemini-2.0-flash");
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Sem chave própria e sem permissão de usar a do servidor, não há IA — e nada é chamado")
    void shouldReturnEmptyWhenServerFallbackIsNotAllowed() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        AiChatCallerFactory factory = factory(new SecretCipher(KEY_1, "k1", ""));

        assertThat(factory.resolve(user, false)).isEmpty();
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Instalação sem cofre e sem fallback: nem consulta o banco — é a importação de quem não usa IA")
    void shouldNotEvenQueryWhenNoDoorIsOpen() {
        // flag do servidor desligada + SECRET_ENCRYPTION_KEY vazia = o estado da
        // instalação padrão. Antes de o suggester deixar de ser condicional isso
        // custava zero consulta, e precisa continuar custando
        AiChatCallerFactory factory = factory(new SecretCipher("", "k1", ""));

        assertThat(factory.resolve(user, false)).isEmpty();

        verifyNoInteractions(repository);
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Sem cofre mas com fallback permitido, o assistente continua respondendo pelo servidor")
    void shouldStillServeTheAssistantWithoutVault() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        AiChatCallerFactory factory = factory(new SecretCipher("", "k1", ""));

        Optional<AiChatCaller> caller = factory.resolve(user, true);

        assertThat(caller).isPresent();
        assertThat(caller.get().userOwned()).isFalse();
    }

    @Test
    @DisplayName("Com chave própria, a chamada sai nela e para o provedor/modelo escolhidos")
    void shouldUseUserKeyWhenPresent() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(user.getId()))
                .thenReturn(Optional.of(settingsWith(cipher, AiProvider.ANTHROPIC, "claude-sonnet-4-5")));
        when(httpClient.complete(any(), anyString(), anyList(), anyString(), any())).thenReturn("resposta");
        AiChatCallerFactory factory = factory(cipher);

        AiChatCaller caller = factory.resolve(user, true).orElseThrow();
        assertThat(caller.userOwned()).isTrue();
        assertThat(caller.complete("sistema", "pergunta")).isEqualTo("resposta");

        ArgumentCaptor<AiCallTarget> alvo = ArgumentCaptor.forClass(AiCallTarget.class);
        verify(httpClient).complete(alvo.capture(), anyString(), anyList(), anyString(), any(Duration.class));
        assertThat(alvo.getValue().provider()).isEqualTo(AiProvider.ANTHROPIC);
        assertThat(alvo.getValue().model()).isEqualTo("claude-sonnet-4-5");
        assertThat(alvo.getValue().apiKey()).isEqualTo(CHAVE_DO_USUARIO);
        // o alvo é montado a partir do catálogo do CONTEXTO: endpoint sobrescrito
        // pelo perfil de teste e teto de tokens vindo do default curado, os dois
        // ao mesmo tempo — é essa combinação que o mecanismo antigo destruía
        assertThat(alvo.getValue().endpoint()).isEqualTo("https://example.test/anthropic/chat/completions");
        assertThat(alvo.getValue().maxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("Provedor sem teto de tokens não inventa um")
    void shouldNotInventMaxTokensForOtherProviders() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(user.getId()))
                .thenReturn(Optional.of(settingsWith(cipher, AiProvider.OPENAI, "gpt-4o-mini")));
        AiChatCallerFactory factory = factory(cipher);

        AiCallTarget alvo = factory.targetFor(user, settingsWith(cipher, AiProvider.OPENAI, "gpt-4o-mini"));

        assertThat(alvo.maxTokens()).isNull();
        assertThat(alvo.endpoint()).isEqualTo("https://example.test/openai/chat/completions");
        assertThat(factory.resolve(user, false)).isPresent();
    }

    @Test
    @DisplayName("Chave própria ilegível é ERRO — jamais cai em silêncio na chave do servidor")
    void shouldFailInsteadOfSilentlyUsingTheServerKey() {
        SecretCipher original = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(user.getId()))
                .thenReturn(Optional.of(settingsWith(original, AiProvider.OPENAI, "gpt-4o-mini")));

        // o ambiente subiu com outra chave-mestra e sem manter a antiga
        AiChatCallerFactory factory = factory(new SecretCipher(KEY_2, "k2", ""));

        assertThatThrownBy(() -> factory.resolve(user, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cadastre a chave novamente");
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Nenhuma mensagem de erro do resolvedor carrega a chave do usuário")
    void resolveErrorShouldNotLeakTheKey() {
        SecretCipher original = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(user.getId()))
                .thenReturn(Optional.of(settingsWith(original, AiProvider.OPENAI, "gpt-4o-mini")));
        AiChatCallerFactory factory = factory(new SecretCipher(KEY_2, "k2", ""));

        assertThatThrownBy(() -> factory.resolve(user, true))
                .hasMessageNotContaining(CHAVE_DO_USUARIO);
    }
}
