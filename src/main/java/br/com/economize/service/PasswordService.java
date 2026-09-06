package br.com.economize.service;

import br.com.economize.model.PasswordResetToken;
import br.com.economize.model.User;
import br.com.economize.repository.PasswordResetTokenRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
@Service
public class PasswordService {

    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);
    private static final int RESET_TOKEN_BYTES = 32;
    // Mensagem única para hash desconhecido, expirado ou já usado: detalhar o
    // motivo daria a um atacante um oráculo sobre o estado dos tokens
    private static final String INVALID_TOKEN_MESSAGE = "Token inválido ou expirado";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String resetUrlBase;

    public PasswordService(UserRepository userRepository,
                           PasswordResetTokenRepository tokenRepository,
                           PasswordEncoder passwordEncoder,
                           EmailSender emailSender,
                           @Value("${economize.app.reset-url-base:https://economize-web.onrender.com/reset-password}") String resetUrlBase) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.resetUrlBase = resetUrlBase;
    }

    /**
     * Gera e envia o token de recuperação quando o e-mail existe. Nunca lança
     * erro nem devolve nada distinguível para e-mail inexistente: a resposta
     * ao cliente é sempre neutra (anti-enumeração de contas).
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email).ifPresentOrElse(this::issueResetToken,
                // sem dado do usuário no log para não virar canal de enumeração
                () -> log.info("Recuperação de senha solicitada para e-mail não cadastrado; resposta neutra"));
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = tokenRepository.findByTokenHash(sha256Hex(token))
                .filter(t -> t.getUsedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(OffsetDateTime.now()))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_TOKEN_MESSAGE));

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        // Redefinir por e-mail também resolve a pendência da senha provisória
        // (V21): quem chegou aqui provou o acesso à caixa de entrada e acabou
        // de escolher uma senha que só ela conhece
        user.setMustChangePassword(false);
        userRepository.save(user);

        resetToken.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(resetToken);
        log.info("Senha redefinida via token de recuperação para o usuário {}", user.getId());
    }

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        // A troca cumpre a pendencia da senha provisoria (V21): daqui em diante
        // a unica pessoa que sabe a senha e o dono da conta
        user.setMustChangePassword(false);
        userRepository.save(user);
        log.info("Senha alterada pelo próprio usuário {}", user.getId());
    }

    private void issueResetToken(User user) {
        // Purga oportunista: sem job agendado no projeto, é este o momento em que
        // tokens vencidos (usados inclusive, o TTL já passou) saem da tabela —
        // sem isso ela cresceria para sempre
        tokenRepository.deleteByExpiresAtBefore(OffsetDateTime.now());

        // Um token válido por vez: pedir de novo invalida os anteriores não usados
        tokenRepository.deleteByUserIdAndUsedAtIsNull(user.getId());

        String token = generateToken();
        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(sha256Hex(token))
                .expiresAt(OffsetDateTime.now().plus(RESET_TOKEN_TTL))
                .build());

        try {
            emailSender.sendPasswordResetEmail(user.getEmail(), resetUrlBase + "?token=" + token);
        } catch (RuntimeException e) {
            // Falha no envio não pode virar 5xx só quando a conta existe:
            // isso reabriria o canal de enumeração que a resposta neutra fecha
            log.error("Falha ao enviar e-mail de recuperação de senha", e);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[RESET_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; se faltar, o ambiente está quebrado
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
