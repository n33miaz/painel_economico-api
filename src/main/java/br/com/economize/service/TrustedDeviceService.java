package br.com.economize.service;

import br.com.economize.dto.auth.TrustedDeviceResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.TrustedDevice;
import br.com.economize.model.User;
import br.com.economize.repository.TrustedDeviceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aparelhos que já provaram quem são — e o aviso quando alguém entra de um que
 * não provou.
 *
 * <p><b>O que isto muda no login.</b> Com um segundo fator ativo, o código
 * passa a ser pedido só em aparelho DESCONHECIDO. No celular de todo dia o
 * login volta a ser e-mail e senha — que é a diferença entre um fator que o
 * dono mantém ligado e um que ele desliga na primeira semana.
 *
 * <p><b>Por que o segredo, e não o IP.</b> Ver o comentário longo da V22: IP de
 * celular muda a cada torre, e "internet nova" como porteiro faria o app pedir
 * código o dia inteiro. O IP entra por outro caminho — o AVISO de acesso de
 * lugar novo, que é informação e não bloqueio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustedDeviceService {

    /**
     * Noventa dias. Aparelho esquecido num lugar não pode ser porta aberta para
     * sempre; renovar custa um código.
     */
    private static final int TRUST_DAYS = 90;

    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final TrustedDeviceRepository deviceRepository;
    private final EmailSender emailSender;
    private final SecureRandom random = new SecureRandom();

    /** O que o cliente manda no login, ou nulo. */
    public record DeviceHint(String token, String label, String ip) {
    }

    /**
     * Este aparelho já é conhecido desta conta?
     *
     * <p>Falso para segredo ausente, desconhecido, vencido ou de OUTRA conta —
     * os quatro são o mesmo do ponto de vista de quem chega: pede o código.
     */
    @Transactional
    public boolean isTrusted(User user, DeviceHint hint) {
        if (hint == null || hint.token() == null || hint.token().isBlank()) return false;
        Optional<TrustedDevice> found = deviceRepository.findByTokenHash(sha256(hint.token()));
        if (found.isEmpty()) return false;
        TrustedDevice device = found.get();
        if (!device.getUser().getId().equals(user.getId())) {
            // segredo válido apresentado na conta errada: além de negar, é o
            // tipo de coisa que se quer saber que aconteceu
            log.warn("Segredo de aparelho apresentado por outra conta — negado. user={}", user.getEmail());
            return false;
        }
        if (device.getExpiresAt().isBefore(OffsetDateTime.now())) return false;

        device.setLastUsedAt(OffsetDateTime.now());
        String ipHash = hint.ip() == null ? null : sha256(hint.ip());
        // Endereço novo no aparelho conhecido: avisa, mas NÃO barra. Quem viaja
        // ou troca de operadora não pode ser tratado como invasor
        if (ipHash != null && device.getLastIpHash() != null && !ipHash.equals(device.getLastIpHash())) {
            notifyNewLocation(user, device.getLabel());
        }
        device.setLastIpHash(ipHash);
        deviceRepository.save(device);
        return true;
    }

    /**
     * Lembra este aparelho e devolve o segredo — a ÚNICA vez que ele existe
     * fora do aparelho. No banco fica só o hash.
     */
    @Transactional
    public String remember(User user, String label, String ip) {
        // Purga oportunista: sem job agendado, é aqui que os vencidos saem
        deviceRepository.deleteByExpiresAtBefore(OffsetDateTime.now());

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        deviceRepository.save(TrustedDevice.builder()
                .user(user)
                .tokenHash(sha256(token))
                .label(label == null || label.isBlank() ? null : truncate(label.trim()))
                .lastIpHash(ip == null ? null : sha256(ip))
                .lastUsedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusDays(TRUST_DAYS))
                .build());
        log.info("Aparelho lembrado para user={} por {} dias", user.getEmail(), TRUST_DAYS);
        return token;
    }

    public List<TrustedDeviceResponse> list(String email) {
        User user = requireUser(email);
        return deviceRepository.findAllByUserIdOrderByLastUsedAtDesc(user.getId()).stream()
                .map(TrustedDeviceResponse::from)
                .toList();
    }

    /** Esquece um aparelho: o próximo login dele volta a pedir código. */
    @Transactional
    public void forget(String email, UUID deviceId) {
        User user = requireUser(email);
        TrustedDevice device = deviceRepository.findByIdAndUserId(deviceId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Aparelho não encontrado"));
        deviceRepository.delete(device);
    }

    /** Esquece todos — o botão de "perdi o celular". */
    @Transactional
    public void forgetAll(String email) {
        User user = requireUser(email);
        deviceRepository.deleteAllByUserId(user.getId());
        log.info("Todos os aparelhos esquecidos para user={}", user.getEmail());
    }

    /**
     * Avisa por e-mail que a conta foi aberta de um lugar novo.
     *
     * <p><b>Estado real:</b> este caminho existe e é chamado, mas hoje ele não
     * ENVIA nada — sem as variáveis {@code MAIL_*} no ambiente, o
     * {@code LogEmailSender} só registra em log (ver
     * {@code economize.mail.enabled}). O código fica aqui pronto de propósito:
     * no dia em que houver SMTP, o aviso passa a sair sozinho, sem mudança
     * nenhuma. Enquanto não houver, o registro em log é o que existe — e é
     * melhor do que um aviso que ninguém escreveu.
     */
    private void notifyNewLocation(User user, String label) {
        try {
            emailSender.sendSecurityAlert(user.getEmail(),
                    "Acesso à sua conta a partir de uma rede diferente"
                            + (label == null ? "" : " (" + label + ")")
                            + ". Se foi você, não há nada a fazer. Se não foi, troque sua senha e "
                            + "esqueça os aparelhos conhecidos em Perfil › Segurança.");
        } catch (Exception e) {
            // Aviso é acessório: falhar aqui não pode derrubar um login válido
            log.warn("Não foi possível enviar o aviso de acesso ({})", e.getClass().getSimpleName());
        }
    }

    private static String truncate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
