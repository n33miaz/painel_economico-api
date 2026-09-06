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
import br.com.economize.service.TrustedDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
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
    private final TrustedDeviceService deviceService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          PasswordService passwordService, MfaService mfaService,
                          TrustedDeviceService deviceService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.passwordService = passwordService;
        this.mfaService = mfaService;
        this.deviceService = deviceService;
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
                    + "resposta é a de sempre — token e nome, e nenhum campo novo. "
                    + "Com `deviceToken` de um aparelho já lembrado, o segundo passo é DISPENSADO: o código "
                    + "só é pedido em aparelho desconhecido.")
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@RequestBody AuthRequest request,
                                                    ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new IllegalArgumentException("Credenciais inválidas"));

            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                throw new IllegalArgumentException("Credenciais inválidas");
            }

            // O fator entra ANTES do carimbo de último acesso: senha certa com
            // segundo passo pendente não é acesso, e registrar como se fosse
            // apagaria justamente o sinal que denuncia o vazamento da senha.
            //
            // Aparelho já conhecido dispensa o segundo passo. Sem isso o código
            // seria pedido dez vezes por dia no celular do dono — e fator que
            // atrapalha o dono é fator que o dono desliga.
            if (mfaService.isEnabledFor(user)
                    && !deviceService.isTrusted(user, hintFrom(request, exchange))) {
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
    public Mono<ResponseEntity<AuthResponse>> loginMfa(@Valid @RequestBody MfaChallengeRequest request,
                                                        ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String email = jwtUtil.emailFromMfaChallenge(request.mfaToken());
            if (email == null) throw new IllegalArgumentException("Desafio inválido");

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Desafio inválido"));

            if (!mfaService.verify(user, request.code())) {
                throw new IllegalArgumentException("Código inválido");
            }

            // Lembrar o aparelho só depois de o código conferir: é o segundo
            // fator que autoriza a dispensa dele nas próximas vezes
            String deviceToken = request.rememberDevice()
                    ? deviceService.remember(user, request.deviceLabel(), clientIp(exchange))
                    : null;

            AuthResponse resposta = new AuthResponse(
                    jwtUtil.generateToken(user.getEmail()), completeLogin(user));
            resposta.setDeviceToken(deviceToken);
            return ResponseEntity.ok(resposta);

        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    private TrustedDeviceService.DeviceHint hintFrom(AuthRequest request, ServerWebExchange exchange) {
        return new TrustedDeviceService.DeviceHint(
                request.getDeviceToken(), request.getDeviceLabel(), clientIp(exchange));
    }

    /**
     * O IP de quem chamou. Atrás do proxy do Render o endereço do soquete é o do
     * proxy, e o do cliente vem em {@code X-Forwarded-For} — o PRIMEIRO da
     * lista, que é quem entrou; os seguintes são os saltos.
     *
     * <p>Só serve ao AVISO de acesso de lugar novo, nunca a barrar nada:
     * cabeçalho vindo do cliente é palpite, não prova.
     */
    private String clientIp(ServerWebExchange exchange) {
        if (exchange == null) return null;
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? null : remote.getAddress().getHostAddress();
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
