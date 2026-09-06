package br.com.economize.service;

import br.com.economize.dto.auth.MfaRecoveryCodesResponse;
import br.com.economize.dto.auth.MfaSetupResponse;
import br.com.economize.dto.auth.MfaStatusResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.MfaRecoveryCode;
import br.com.economize.model.User;
import br.com.economize.model.UserMfa;
import br.com.economize.repository.MfaRecoveryCodeRepository;
import br.com.economize.repository.TrustedDeviceRepository;
import br.com.economize.repository.UserMfaRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.MfaSecretCipher;
import br.com.economize.security.SecretCipher;
import br.com.economize.security.TotpGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Segundo fator por TOTP — cadastro, confirmação, verificação e desligamento.
 *
 * <p>O ciclo tem três estados, e a diferença entre os dois primeiros é o que
 * impede alguém de se trancar para fora: <b>ausente</b> (nenhuma linha),
 * <b>pendente</b> (segredo gerado, QR na tela, ainda NÃO exigido no login) e
 * <b>ativo</b> (um código já conferiu). Só o terceiro muda o login.
 *
 * <p>Refazer o cadastro enquanto pendente gera um segredo novo de propósito: é
 * o caminho de quem leu o QR num aparelho e quer refazer noutro. Com o fator já
 * ativo, o cadastro é recusado — trocar de aparelho passa por desligar (com
 * senha) e ligar de novo.
 */
@Slf4j
@Service
public class MfaService {

    /**
     * Dez códigos de 10 caracteres em Base32 (50 bits cada). Dez é o número que
     * o usuário consegue guardar num papel; 50 bits é o que torna adivinhar um
     * deles indistinguível de adivinhar a senha.
     */
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_CHARS = 10;

    /** Sem I, O, 0 e 1: o código é lido de um papel e digitado à mão. */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final String ISSUER = "Economize!";

    private final UserRepository userRepository;
    private final UserMfaRepository mfaRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaSecretCipher cipher;
    private final TrustedDeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    /**
     * O relógio existe como campo porque a proteção contra replay é uma regra
     * SOBRE O TEMPO: "o mesmo código não entra duas vezes" só é demonstrável
     * fazendo o tempo andar. Em produção é sempre o do sistema.
     */
    private final Clock clock;

    // Explícito porque a classe tem DOIS construtores (o de baixo recebe o
    // relógio): com mais de um, o Spring não escolhe sozinho e o contexto nem
    // sobe — "No default constructor found"
    @Autowired
    public MfaService(UserRepository userRepository,
                      UserMfaRepository mfaRepository,
                      MfaRecoveryCodeRepository recoveryCodeRepository,
                      MfaSecretCipher cipher,
                      TrustedDeviceRepository deviceRepository,
                      PasswordEncoder passwordEncoder) {
        this(userRepository, mfaRepository, recoveryCodeRepository, cipher, deviceRepository,
                passwordEncoder, Clock.systemUTC());
    }

    MfaService(UserRepository userRepository,
               UserMfaRepository mfaRepository,
               MfaRecoveryCodeRepository recoveryCodeRepository,
               MfaSecretCipher cipher,
               TrustedDeviceRepository deviceRepository,
               PasswordEncoder passwordEncoder,
               Clock clock) {
        this.userRepository = userRepository;
        this.mfaRepository = mfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.cipher = cipher;
        this.deviceRepository = deviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public MfaStatusResponse status(String email) {
        User user = requireUser(email);
        Optional<UserMfa> mfa = mfaRepository.findByUserId(user.getId());
        boolean enabled = mfa.map(UserMfa::isEnabled).orElse(false);
        return new MfaStatusResponse(
                enabled,
                mfa.isPresent() && !enabled,
                mfa.map(UserMfa::getConfirmedAt).orElse(null),
                enabled ? recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()) : 0);
    }

    /**
     * Gera (ou regenera) o segredo e devolve o que a tela mostra uma única vez.
     * Enquanto não confirmado, o login segue exigindo só a senha.
     */
    @Transactional
    public MfaSetupResponse startSetup(String email) {
        User user = requireUser(email);
        UserMfa existing = mfaRepository.findByUserId(user.getId()).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw new IllegalArgumentException(
                    "O segundo fator já está ativo — desligue-o antes de cadastrar outro aparelho");
        }

        String secret = TotpGenerator.newSecret();
        String envelope = cipher.encrypt(secret, user.getId().toString());
        if (existing == null) {
            mfaRepository.save(UserMfa.builder()
                    .user(user)
                    .secretCipher(envelope)
                    .enabled(false)
                    .build());
        } else {
            // segredo novo zera o passo consumido: ele pertencia ao anterior
            existing.setSecretCipher(envelope);
            existing.setLastUsedStep(null);
            mfaRepository.save(existing);
        }
        return new MfaSetupResponse(secret, TotpGenerator.otpauthUri(ISSUER, user.getEmail(), secret));
    }

    /**
     * Confirma o cadastro com o primeiro código. É aqui que o fator passa a
     * valer — e é aqui que nascem os códigos de recuperação, que a resposta
     * mostra em claro pela única vez.
     */
    @Transactional
    public MfaRecoveryCodesResponse activate(String email, String code) {
        User user = requireUser(email);
        UserMfa mfa = mfaRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nenhum cadastro de segundo fator em andamento"));
        if (mfa.isEnabled()) {
            throw new IllegalArgumentException("O segundo fator já está ativo");
        }
        Long step = TotpGenerator.matchingStep(decrypt(mfa, user), code, now());
        if (step == null) {
            throw new IllegalArgumentException(
                    "Código inválido — confira o relógio do aparelho e tente de novo");
        }
        mfa.setEnabled(true);
        mfa.setConfirmedAt(OffsetDateTime.now(clock));
        mfa.setLastUsedStep(step);
        mfaRepository.save(mfa);
        log.info("Segundo fator ativado para user={}", email);
        return new MfaRecoveryCodesResponse(regenerateRecoveryCodes(user));
    }

    /**
     * Confere o código do login. Aceita o TOTP ou um código de recuperação —
     * quem perdeu o aparelho não teria outro caminho de volta.
     *
     * @return true se conferiu; o consumo (passo gasto, código riscado) já foi gravado
     */
    @Transactional
    public boolean verify(User user, String code) {
        UserMfa mfa = mfaRepository.findByUserId(user.getId()).orElse(null);
        if (mfa == null || !mfa.isEnabled()) return false;

        String secret;
        try {
            secret = decrypt(mfa, user);
        } catch (SecretCipher.Unreadable e) {
            // JWT_SECRET trocado desde o cadastro: o TOTP virou ilegível, mas os
            // códigos de recuperação são hash e não dependem de chave nenhuma —
            // é exatamente para isto que eles existem
            log.warn("Segredo TOTP ilegível para user={} — só os códigos de recuperação entram. {}",
                    user.getEmail(), e.getMessage());
            return consumeRecoveryCode(user, code);
        }

        Long step = TotpGenerator.matchingStep(secret, code, now());
        if (step == null) return consumeRecoveryCode(user, code);
        // Replay: dentro dos 30 segundos de vida, o mesmo código só entra uma vez
        if (mfa.getLastUsedStep() != null && step <= mfa.getLastUsedStep()) return false;
        mfa.setLastUsedStep(step);
        mfaRepository.save(mfa);
        return true;
    }

    /** Novo lote de códigos; o lote anterior deixa de valer. */
    @Transactional
    public MfaRecoveryCodesResponse rotateRecoveryCodes(String email) {
        User user = requireUser(email);
        UserMfa mfa = mfaRepository.findByUserId(user.getId()).orElse(null);
        if (mfa == null || !mfa.isEnabled()) {
            throw new IllegalArgumentException("O segundo fator não está ativo");
        }
        return new MfaRecoveryCodesResponse(regenerateRecoveryCodes(user));
    }

    /** Desligar exige a SENHA — ver MfaDisableRequest. */
    @Transactional
    public void disable(String email, String password) {
        User user = requireUser(email);
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("Senha incorreta");
        }
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        mfaRepository.deleteByUserId(user.getId());
        // Os aparelhos conhecidos só existiam para dispensar ESTE fator: sem
        // ele, guardá-los é manter um segredo que não abre mais nada
        deviceRepository.deleteAllByUserId(user.getId());
        log.info("Segundo fator desligado para user={}", email);
    }

    public boolean isEnabledFor(User user) {
        return mfaRepository.findByUserId(user.getId()).map(UserMfa::isEnabled).orElse(false);
    }

    private List<String> regenerateRecoveryCodes(User user) {
        recoveryCodeRepository.deleteAllByUserId(user.getId());
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<MfaRecoveryCode> rows = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = randomRecoveryCode();
            plain.add(code);
            rows.add(MfaRecoveryCode.builder()
                    .user(user)
                    .codeHash(passwordEncoder.encode(normalize(code)))
                    .build());
        }
        recoveryCodeRepository.saveAll(rows);
        return plain;
    }

    private boolean consumeRecoveryCode(User user, String code) {
        String normalized = normalize(code);
        // 10 caracteres é o formato; qualquer outro tamanho nem chega a custar
        // um bcrypt — e o custo do bcrypt é justamente o que um atacante mediria
        if (normalized.length() != RECOVERY_CODE_CHARS) return false;
        for (MfaRecoveryCode candidate : recoveryCodeRepository.findAllByUserIdAndUsedAtIsNull(user.getId())) {
            if (passwordEncoder.matches(normalized, candidate.getCodeHash())) {
                candidate.setUsedAt(OffsetDateTime.now(clock));
                recoveryCodeRepository.save(candidate);
                log.info("Entrada por código de recuperação para user={} — restam {}",
                        user.getEmail(),
                        recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId()));
                return true;
            }
        }
        return false;
    }

    /** Hífen e caixa são cosmética da tela: o mesmo código vale nos dois jeitos. */
    private static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase();
    }

    private String randomRecoveryCode() {
        StringBuilder out = new StringBuilder(RECOVERY_CODE_CHARS);
        for (int i = 0; i < RECOVERY_CODE_CHARS; i++) {
            out.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return out.toString();
    }

    private String decrypt(UserMfa mfa, User user) {
        return cipher.decrypt(mfa.getSecretCipher(), user.getId().toString());
    }

    private Instant now() {
        return clock.instant();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
