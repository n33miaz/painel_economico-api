package br.com.economize.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * TOTP (RFC 6238) — o código de seis dígitos que o app de autenticação mostra.
 *
 * <p>Escrito à mão, e não trazido de uma biblioteca, porque o algoritmo inteiro
 * cabe aqui: HMAC-SHA1 sobre o número do passo de tempo, um truncamento
 * dinâmico e um módulo. O JDK já traz o HMAC; uma dependência a mais para
 * quarenta linhas seria superfície nova para não ganhar nada.
 *
 * <p>Os parâmetros são os PADRÕES do RFC — SHA-1, 6 dígitos, passo de 30
 * segundos — e não é conservadorismo: é o que Google Authenticator, Authy, 1Password
 * e o Aegis leem de um QR sem configuração extra. Mudar qualquer um deles faz o
 * app do usuário gerar um código que nunca confere.
 *
 * <p>Classe pura de propósito: não conhece banco, usuário nem Spring. É o que
 * torna possível testá-la contra os vetores do próprio RFC.
 */
public final class TotpGenerator {

    /** Tamanho do passo, em segundos — o "30s" que a tela do autenticador conta. */
    public static final long STEP_SECONDS = 30L;

    private static final int DIGITS = 6;
    private static final int MODULO = 1_000_000;
    private static final String ALGORITHM = "HmacSHA1";

    /**
     * Quantos passos de tolerância para cada lado. O relógio do celular do
     * usuário nunca está exatamente no do servidor, e um código digitado no
     * segundo 29 chega no segundo 31. Um passo é o mesmo valor que o Google usa;
     * mais do que isso amplia a janela em que um código roubado ainda vale.
     */
    public static final int DRIFT_STEPS = 1;

    /** 20 bytes = 160 bits, o tamanho de chave que o RFC 4226 recomenda para SHA-1. */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpGenerator() {
    }

    /** Segredo novo, já em Base32 — a forma que o QR e a digitação manual usam. */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return toBase32(bytes);
    }

    /** Em que passo de tempo o instante cai. É o contador que entra no HMAC. */
    public static long stepAt(Instant moment) {
        return Math.floorDiv(moment.getEpochSecond(), STEP_SECONDS);
    }

    /** O código daquele passo, com zeros à esquerda ("004815"). */
    public static String codeAt(String base32Secret, long step) {
        byte[] key = fromBase32(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
        byte[] hash = hmac(key, counter);

        // Truncamento dinâmico do RFC 4226: os 4 bits finais escolhem de onde
        // sair os 4 bytes do código, para o resultado não depender sempre da
        // mesma fatia do hash
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format("%0" + DIGITS + "d", binary % MODULO);
    }

    /**
     * Qual passo o código confere, dentro da tolerância — ou {@code null}.
     *
     * <p>Devolve o PASSO e não um booleano de propósito: quem chama precisa
     * gravá-lo para recusar o mesmo código na segunda vez. Sem isso, um código
     * visto por cima do ombro serviria a todas as tentativas dos seus 30
     * segundos de vida.
     */
    public static Long matchingStep(String base32Secret, String code, Instant now) {
        if (code == null) return null;
        String normalized = code.replaceAll("\\s", "");
        if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return null;
        }
        long current = stepAt(now);
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            // comparação em tempo constante: o tempo de resposta não pode dizer
            // quantos dígitos do código estavam certos
            if (constantTimeEquals(codeAt(base32Secret, step), normalized)) {
                return step;
            }
        }
        return null;
    }

    /**
     * A URI {@code otpauth://} que vira o QR code.
     *
     * <p>O rótulo leva o nome do produto e o e-mail — é o que o usuário vê na
     * lista do autenticador, e sem ele três contas viram três "6 dígitos" iguais.
     */
    public static String otpauthUri(String issuer, String accountEmail, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountEmail);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (Exception e) {
            // HmacSHA1 é obrigatório em toda JVM: chegar aqui é ambiente quebrado
            throw new IllegalStateException("HMAC indisponível na JVM", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    static String toBase32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        return out.toString();
    }

    static byte[] fromBase32(String encoded) {
        String clean = encoded.replaceAll("[=\\s]", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Segredo TOTP inválido");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                // o encoder de formulário troca espaço por "+", que numa URI é
                // um "+" literal — o autenticador mostraria "Economize+!"
                .replace("+", "%20");
    }
}
