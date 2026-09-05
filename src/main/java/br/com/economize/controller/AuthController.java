package br.com.economize.controller;

import br.com.economize.dto.auth.AuthRequest;
import br.com.economize.dto.auth.AuthResponse;
import br.com.economize.dto.auth.ForgotPasswordRequest;
import br.com.economize.dto.auth.MfaChallengeRequest;
import br.com.economize.dto.auth.RegisterRequest;
import br.com.economize.dto.auth.ResetPasswordRequest;
import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtUtil;
import br.com.economize.service.MfaService;
import br.com.economize.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@SuppressWarnings("null")
@Tag(name = "Autenticação", description = "Cadastro, login e recuperação de senha")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PasswordService passwordService;
    private final MfaService mfaService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          PasswordService passwordService, MfaService mfaService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.passwordService = passwordService;
        this.mfaService = mfaService;
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(@RequestBody RegisterRequest request) {
        return Mono.fromCallable(() -> {
            if (userRepository.findByEmail(request.getEmail()).isPresent()) {
                throw new IllegalArgumentException("Email já cadastrado");
            }

            User user = User.builder()
                    .name(request.getName())
                    .email(request.getEmail())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .build();

            userRepository.save(user);
            String token = jwtUtil.generateToken(user.getEmail());
            return ResponseEntity.ok(new AuthResponse(token, user.getName()));

        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).build()));
    }

    @Operation(summary = "Entrar",
            description = "Com segundo fator ativo a resposta NÃO traz token: vem `mfaRequired: true` e um "
                    + "`mfaToken` de 5 minutos, que só serve para POST /auth/login/mfa. Sem segundo fator a "
                    + "resposta é a de sempre — token e nome, e nenhum campo novo.")
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@RequestBody AuthRequest request) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("Credenciais inválidas"));

            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new IllegalArgumentException("Credenciais inválidas");
            }

            // O fator entra ANTES do carimbo de último acesso: senha certa com
            // segundo passo pendente não é acesso, e registrar como se fosse
            // apagaria justamente o sinal que denuncia o vazamento da senha
            if (mfaService.isEnabledFor(user)) {
                return ResponseEntity.ok(AuthResponse.challenge(jwtUtil.generateMfaChallenge(user.getEmail())));
            }

            return ResponseEntity.ok(new AuthResponse(jwtUtil.generateToken(user.getEmail()), completeLogin(user)));

        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    @Operation(operationId = "loginMfa", summary = "Segundo passo do login",
            description = "Troca o desafio + o código (do autenticador ou de recuperação) pela sessão. "
                    + "Desafio expirado, adulterado ou código errado respondem o MESMO 401: a resposta não "
                    + "pode dizer qual das duas coisas falhou.")
    @PostMapping("/login/mfa")
    public Mono<ResponseEntity<AuthResponse>> loginMfa(@Valid @RequestBody MfaChallengeRequest request) {
        return Mono.fromCallable(() -> {
            String email = jwtUtil.emailFromMfaChallenge(request.mfaToken());
            if (email == null) throw new IllegalArgumentException("Desafio inválido");

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Desafio inválido"));

            if (!mfaService.verify(user, request.code())) {
                throw new IllegalArgumentException("Código inválido");
            }

            return ResponseEntity.ok(new AuthResponse(jwtUtil.generateToken(user.getEmail()), completeLogin(user)));

        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    /** Carimba o último acesso e devolve o nome que a resposta do login leva. */
    private String completeLogin(User user) {
        // alimenta o "último acesso" da tela de informações do usuário
        user.setLastLoginAt(java.time.OffsetDateTime.now());
        userRepository.save(user);
        return user.getName();
    }

    @Operation(operationId = "forgotPassword", summary = "Solicitar recuperação de senha",
            description = "Resposta sempre neutra (202), exista ou não a conta, para impedir enumeração de e-mails.")
    @PostMapping("/forgot-password")
    public Mono<ResponseEntity<Map<String, String>>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return Mono.fromCallable(() -> {
            passwordService.forgotPassword(request.email());
            return ResponseEntity.accepted().body(
                    Map.of("message", "Se o e-mail existir, enviaremos instruções para redefinir a senha."));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(operationId = "resetPassword", summary = "Redefinir senha com token de recuperação",
            description = "Token inválido, expirado ou já usado retorna 400 com mensagem neutra.")
    @PostMapping("/reset-password")
    public Mono<ResponseEntity<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return Mono.fromCallable(() -> {
            passwordService.resetPassword(request.token(), request.newPassword());
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
