package br.com.economize.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cofre do segredo TOTP.
 *
 * <p>Mesmo envelope autodescritivo do {@link SecretCipher}
 * ({@code v1:<idDaChave>:<iv>:<cifra>}, AES-256-GCM, UUID do dono como dado
 * autenticado), mas com uma origem de chave diferente — e a diferença é o ponto
 * desta classe.
 *
 * <p><b>Por que não reusar o SecretCipher.</b> A chave-mestra dele é a
 * {@code SECRET_ENCRYPTION_KEY}, OPCIONAL no deploy: hoje, em produção, ela não
 * existe ({@code byokAvailable=false}) e a feature de chave própria de IA está
 * desligada por causa disso. Isso é aceitável para um extra; não é aceitável
 * para um segundo fator, que precisa poder ser ligado em qualquer instalação —
 * um fator de segurança que só funciona quando uma variável opcional foi
 * lembrada é um fator que ninguém tem.
 *
 * <p><b>De onde vem a chave, então.</b> De {@code jwt.secret}, que a API já
 * exige para subir, via HMAC-SHA256 com um rótulo fixo. Não é a chave de
 * assinatura reusada: é uma derivada de 256 bits que só serve a este uso — quem
 * conhecer os segredos TOTP não ganha nada para forjar tokens, e vice-versa.
 *
 * <p><b>O preço, dito em voz alta.</b> Trocar o {@code JWT_SECRET} torna os
 * segredos guardados ILEGÍVEIS. Isso já é uma operação de martelo (invalida
 * toda sessão emitida); o que se soma é que cada usuário com fator ativo
 * precisará recadastrá-lo. A porta de saída existe e é justamente para isto: os
 * códigos de recuperação são hash bcrypt, não dependem de chave nenhuma e
 * continuam entrando.
 */
@Component
public class MfaSecretCipher {

    private static final String ENVELOPE_VERSION = "v1";
    private static final String KEY_ID = "jwt-derived";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String DERIVATION_LABEL = "economize-mfa-secret-v1";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(@Value("${jwt.secret}") String jwtSecret) {
        this.key = new SecretKeySpec(derive(jwtSecret), "AES");
    }

    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return String.join(":", ENVELOPE_VERSION, KEY_ID, encode(iv), encode(sealed));
        } catch (Exception e) {
            // sem o texto em claro e sem a mensagem original: exceção de provider
            // de cripto às vezes ecoa tamanho e formato do material
            throw new IllegalStateException("Falha ao cifrar o segredo do fator ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * @throws SecretCipher.Unreadable envelope adulterado, contexto diferente ou
     *                                 JWT_SECRET trocado desde a gravação
     */
    public String decrypt(String envelope, String context) {
        String[] parts = envelope == null ? new String[0] : envelope.split(":");
        if (parts.length != 4 || !ENVELOPE_VERSION.equals(parts[0]) || !KEY_ID.equals(parts[1])) {
            throw new SecretCipher.Unreadable("Formato de envelope não reconhecido");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, decode(parts[2])));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(decode(parts[3])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecretCipher.Unreadable("Segredo do fator não pôde ser decifrado ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] derive(String jwtSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // 32 bytes na saída do SHA-256: exatamente o que AES-256 pede
            return mac.doFinal(DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC indisponível na JVM", e);
        }
    }

    private static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] decode(String data) {
        return Base64.getUrlDecoder().decode(data);
    }
}
