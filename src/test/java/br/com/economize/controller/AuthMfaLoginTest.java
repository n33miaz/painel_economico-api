package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.auth.MfaChallengeRequest;
import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.MfaService;
import br.com.economize.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O login em dois passos.
 *
 * <p>Quatro coisas precisam ser verdade ao mesmo tempo, e é por isso que este
 * teste existe separado do {@code AuthControllerTest}: quem NÃO usa o fator
 * recebe a resposta de sempre (contrato do APK publicado); quem usa não recebe
 * token nenhum no primeiro passo; o desafio emitido lá NÃO abre a API; e o
 * segundo passo recusa desafio adulterado com o mesmo 401 do código errado.
 */
@WebFluxTest(AuthController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
@DisplayName("Login em dois passos")
class AuthMfaLoginTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordService passwordService;

    @MockitoBean
    private MfaService mfaService;

    private User ana;

    @BeforeEach
    void setUp() {
        ana = User.builder()
                .id(UUID.randomUUID())
                .name("Ana")
                .email("ana@example.com")
                .password(new BCryptPasswordEncoder(4).encode("senha-da-ana"))
                .build();
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(ana));
    }

    @Test
    @DisplayName("sem segundo fator, a resposta é a de sempre — token, nome, e nada mais")
    void withoutMfaTheContractIsUnchanged() {
        when(mfaService.isEnabledFor(ana)).thenReturn(false);

        webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "ana@example.com", "password", "senha-da-ana"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.name").isEqualTo("Ana")
                // o APK publicado lê estes dois campos; campo novo em resposta
                // de quem não usa MFA seria mudança de contrato sem motivo
                .jsonPath("$.mfaRequired").doesNotExist()
                .jsonPath("$.mfaToken").doesNotExist();
    }

    @Test
    @DisplayName("com segundo fator, o primeiro passo NÃO devolve sessão")
    void withMfaTheFirstStepHandsOutNoSession() {
        when(mfaService.isEnabledFor(ana)).thenReturn(true);

        webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "ana@example.com", "password", "senha-da-ana"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mfaRequired").isEqualTo(true)
                .jsonPath("$.mfaToken").isNotEmpty()
                .jsonPath("$.token").doesNotExist();

        // senha certa com segundo passo pendente não é acesso: carimbar o
        // último login aqui apagaria o sinal que denuncia o vazamento da senha
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("o desafio do primeiro passo não abre a API")
    void theChallengeIsNotASession() {
        String challenge = jwtUtil.generateMfaChallenge("ana@example.com");

        // /users/me é rota autenticada; com o desafio no cabeçalho continua 401
        webTestClient.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + challenge)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("o segundo passo troca o desafio pela sessão")
    void secondStepIssuesTheSession() {
        when(mfaService.verify(any(User.class), anyString())).thenReturn(true);
        String challenge = jwtUtil.generateMfaChallenge("ana@example.com");

        webTestClient.post().uri("/api/v1/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MfaChallengeRequest(challenge, "123456"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.name").isEqualTo("Ana");

        // agora sim é acesso: o carimbo de último login sai daqui
        verify(userRepository).save(ana);
    }

    @Test
    @DisplayName("código errado responde 401 e não emite sessão")
    void secondStepRejectsWrongCode() {
        when(mfaService.verify(any(User.class), anyString())).thenReturn(false);
        String challenge = jwtUtil.generateMfaChallenge("ana@example.com");

        webTestClient.post().uri("/api/v1/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MfaChallengeRequest(challenge, "000000"))
                .exchange()
                .expectStatus().isUnauthorized();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("uma sessão comum não serve de desafio")
    void aPlainSessionIsNotAChallenge() {
        // sem esta recusa, quem já tem token de alguém pularia o primeiro passo
        String sessao = jwtUtil.generateToken("ana@example.com");

        webTestClient.post().uri("/api/v1/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MfaChallengeRequest(sessao, "123456"))
                .exchange()
                .expectStatus().isUnauthorized();

        verify(mfaService, never()).verify(any(User.class), anyString());
    }

    @Test
    @DisplayName("desafio adulterado responde o MESMO 401 do código errado")
    void aTamperedChallengeLooksLikeAWrongCode() {
        String challenge = jwtUtil.generateMfaChallenge("ana@example.com");
        String tampered = challenge.substring(0, challenge.length() - 2) + "xy";

        webTestClient.post().uri("/api/v1/auth/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MfaChallengeRequest(tampered, "123456"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("senha errada não chega nem a consultar o fator")
    void wrongPasswordStopsBeforeTheFactor() {
        webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "ana@example.com", "password", "chute"))
                .exchange()
                .expectStatus().isUnauthorized();

        // a resposta não pode revelar se a conta tem segundo fator
        verify(mfaService, never()).isEnabledFor(any(User.class));
    }
}
