package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.auth.ForgotPasswordRequest;
import br.com.economize.dto.auth.ResetPasswordRequest;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.MfaService;
import br.com.economize.service.TrustedDeviceService;
import br.com.economize.service.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@WebFluxTest(AuthController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordService passwordService;

    // O login consulta o segundo fator; sem o bean o contexto do WebFluxTest
    // nem sobe. O login em dois passos tem suite propria: AuthMfaLoginTest
    @MockitoBean
    private MfaService mfaService;

    // O login consulta os aparelhos conhecidos antes de exigir o segundo passo
    @MockitoBean
    private TrustedDeviceService deviceService;

    @Test
    @DisplayName("POST /forgot-password - Deve responder 202 com mensagem neutra")
    void forgotPasswordShouldReturnNeutral202() {
        webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ForgotPasswordRequest("ana@economize.dev"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("Se o e-mail existir, enviaremos instruções para redefinir a senha.");

        verify(passwordService).forgotPassword("ana@economize.dev");
    }

    @Test
    @DisplayName("POST /forgot-password - E-mail malformado deve retornar 400 sem chamar o service")
    void forgotPasswordShouldRejectMalformedEmail() {
        webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ForgotPasswordRequest("nao-e-um-email"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(passwordService, never()).forgotPassword(anyString());
    }

    @Test
    @DisplayName("POST /reset-password - Token válido deve retornar 204")
    void resetPasswordShouldReturn204OnSuccess() {
        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-valido", "NovaSenha123"))
                .exchange()
                .expectStatus().isNoContent();

        verify(passwordService).resetPassword("token-valido", "NovaSenha123");
    }

    @Test
    @DisplayName("POST /reset-password - Token inválido deve retornar 400 ProblemDetail neutro")
    void resetPasswordShouldReturn400ForInvalidToken() {
        doThrow(new IllegalArgumentException("Token inválido ou expirado"))
                .when(passwordService).resetPassword(anyString(), anyString());

        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-invalido", "NovaSenha123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Token inválido ou expirado");
    }

    @Test
    @DisplayName("POST /reset-password - Senha com menos de 8 caracteres deve retornar 400")
    void resetPasswordShouldRejectShortPassword() {
        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-valido", "curta"))
                .exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(passwordService);
    }
}
