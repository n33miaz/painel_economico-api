package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.dto.ai.ChatTurn;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.ai.AiChatCaller;
import br.com.economize.service.ai.AiChatCallerFactory;
import br.com.economize.service.ai.AiProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O assistente depois do EC-107.
 *
 * <p>Duas afirmações precisam valer ao mesmo tempo: quem NÃO configurou nada
 * continua sendo atendido pela chave do servidor, exatamente como o APK
 * publicado espera; e quem configurou tem a chamada saindo na chave dele, sem
 * cair em silêncio no servidor quando algo dá errado — trocar o destino dos
 * dados financeiros de alguém sem avisar seria pior do que falhar.
 */
class AiAssistantServiceTest {

    private static final String EMAIL = "dono@economize.app";

    private AiChatCallerFactory factory;
    private UserRepository userRepository;
    private BankTransactionRepository bankTransactionRepository;
    private TransactionRepository transactionRepository;
    private AiChatCaller caller;
    private User user;

    private AiAssistantService service;

    @BeforeEach
    void setUp() {
        factory = mock(AiChatCallerFactory.class);
        userRepository = mock(UserRepository.class);
        bankTransactionRepository = mock(BankTransactionRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        caller = mock(AiChatCaller.class);

        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId())).thenReturn(List.of(
                BankTransaction.builder()
                        .id(UUID.randomUUID())
                        .type("DEBIT")
                        .amount(new BigDecimal("-120.50"))
                        .description("PAG*FITMAX 4321 SAO PAULO BRA")
                        .date(OffsetDateTime.parse("2026-08-10T12:00:00Z"))
                        .build()));
        when(transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId())).thenReturn(List.of());

        service = new AiAssistantService(factory, userRepository,
                bankTransactionRepository, transactionRepository);
    }

    @Test
    @DisplayName("Sem chave própria o assistente responde pela chave do SERVIDOR — comportamento do APK publicado")
    void shouldAnswerThroughTheServerKeyByDefault() {
        when(caller.userOwned()).thenReturn(false);
        when(caller.describe()).thenReturn("chave do servidor / gemini-2.0-flash");
        when(caller.complete(anyString(), anyList(), anyString())).thenReturn("Você gastou R$ 120,50 na academia.");
        // true: o assistente ACEITA o fallback, e é isso que garante que quem
        // nunca abriu a tela de IA continue sendo atendido
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));

        String resposta = service.askAssistant(EMAIL, "quanto gastei?").block();

        assertThat(resposta).isEqualTo("Você gastou R$ 120,50 na academia.");
    }

    @Test
    @DisplayName("Com chave própria a chamada sai nela, e o prompt é o mesmo")
    void shouldAnswerThroughTheUserKeyWhenConfigured() {
        when(caller.userOwned()).thenReturn(true);
        when(caller.describe()).thenReturn("chave do usuário / ANTHROPIC / claude-sonnet-4-5");
        when(caller.complete(anyString(), anyList(), anyString())).thenReturn("resposta do provedor do usuário");
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));

        assertThat(service.askAssistant(EMAIL, "quanto gastei?").block())
                .isEqualTo("resposta do provedor do usuário");
        verify(caller).complete(anyString(), anyList(), eq("quanto gastei?"));
    }

    @Test
    @DisplayName("O contexto financeiro do usuário vai no prompt de sistema, e a pergunta vai separada")
    void shouldBuildTheSystemPromptWithTheFinancialContext() {
        when(caller.complete(anyString(), anyList(), anyString())).thenReturn("ok");
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));

        service.askAssistant(EMAIL, "e o cartão?").block();

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pergunta = ArgumentCaptor.forClass(String.class);
        verify(caller).complete(system.capture(), anyList(), pergunta.capture());
        assertThat(system.getValue())
                .contains("Nino")
                .contains("RESUMO BANCÁRIO")
                .contains("PAG*FITMAX 4321 SAO PAULO BRA");
        assertThat(pergunta.getValue()).isEqualTo("e o cartão?");
    }

    @Test
    @DisplayName("Chave própria ilegível vira erro honesto pedindo recadastro — NUNCA fallback silencioso")
    void unreadableKeyShouldFailInsteadOfFallingBackToTheServer() {
        when(factory.resolve(eq(user), eq(true))).thenThrow(new IllegalArgumentException(
                "Sua chave de IA não pôde ser lida com a configuração atual do servidor. "
                        + "Cadastre a chave novamente nas opções de IA."));

        // aqui o upload não é o que está em jogo: o usuário PERGUNTOU algo, e
        // responder usando a chave (e o provedor) do dono do deploy sem avisar
        // mandaria o extrato dele para outro lugar
        assertThatThrownBy(() -> service.askAssistant(EMAIL, "quanto gastei?").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cadastre a chave novamente");
    }

    @Test
    @DisplayName("Falha do provedor do usuário sobe classificada, para virar 502 com reason")
    void providerFailureShouldSurfaceClassified() {
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));
        when(caller.complete(anyString(), anyList(), anyString())).thenThrow(
                new AiProviderException(AiProviderException.Reason.RATE_LIMIT,
                        "O provedor recusou por limite de uso da sua conta."));

        assertThatThrownBy(() -> service.askAssistant(EMAIL, "quanto gastei?").block())
                .isInstanceOf(AiProviderException.class)
                .hasMessageNotContaining("sk-");
    }

    @Test
    @DisplayName("Usuário inexistente é 400, e nenhuma IA é resolvida")
    void unknownUserShouldNotReachAnyProvider() {
        when(userRepository.findByEmail("fantasma@economize.app")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.askAssistant("fantasma@economize.app", "oi").block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A conversa até aqui vai junto — sem ela o assistente não tem memória")
    void shouldForwardTheConversationHistory() {
        when(caller.complete(anyString(), anyList(), anyString())).thenReturn("ok");
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));
        List<ChatTurn> conversa = List.of(
                new ChatTurn("user", "Quanto gastei com mercado?"),
                new ChatTurn("assistant", "Foram R$ 400."));

        service.askAssistant(EMAIL, "E no mês passado?", conversa).block();

        // Sem isto, "e no mês passado?" chegava ao provedor como uma primeira
        // pergunta solta, e a resposta era necessariamente sobre nada
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> historico = ArgumentCaptor.forClass(List.class);
        verify(caller).complete(anyString(), historico.capture(), eq("E no mês passado?"));
        assertThat(historico.getValue()).isEqualTo(conversa);
    }

    @Test
    @DisplayName("Sem histórico, a chamada é a de sempre: lista vazia, nunca nula")
    void shouldDefaultToAnEmptyHistory() {
        when(caller.complete(anyString(), anyList(), anyString())).thenReturn("ok");
        when(factory.resolve(eq(user), eq(true))).thenReturn(Optional.of(caller));

        service.askAssistant(EMAIL, "quanto gastei?").block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> historico = ArgumentCaptor.forClass(List.class);
        verify(caller).complete(anyString(), historico.capture(), anyString());
        assertThat(historico.getValue()).isEmpty();
    }
}
