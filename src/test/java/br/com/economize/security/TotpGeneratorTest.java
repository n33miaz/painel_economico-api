package br.com.economize.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O gerador de códigos de seis dígitos.
 *
 * <p>A prova que importa não é "o código muda a cada 30 segundos" — é que ele
 * é O MESMO que o aplicativo do usuário calcula. Por isso o primeiro teste roda
 * os vetores publicados no próprio RFC 6238: se esta implementação divergir
 * deles, ela diverge do Google Authenticator, do Authy e de todos os outros, e
 * ninguém consegue ativar o fator.
 */
@DisplayName("TOTP (RFC 6238)")
class TotpGeneratorTest {

    /**
     * A chave do apêndice B do RFC — "12345678901234567890" em ASCII, aqui na
     * mesma Base32 que o QR carrega.
     */
    private static final String RFC_SECRET =
            TotpGenerator.toBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("bate com os vetores publicados no RFC 6238")
    void matchesRfcVectors() {
        // Os quatro primeiros vetores SHA-1 do apêndice B, truncados aos 6
        // dígitos que o produto usa (a tabela do RFC mostra 8)
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(59L))).isEqualTo("287082");
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(1111111109L))).isEqualTo("081804");
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(1111111111L))).isEqualTo("050471");
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(1234567890L))).isEqualTo("005924");
    }

    @Test
    @DisplayName("o zero à esquerda não some — o código tem sempre 6 dígitos")
    void keepsLeadingZeroes() {
        // 005924 é justamente um vetor do RFC que começa com zeros: sem o
        // format com padding, o app mostraria "5924" e o usuário digitaria isso
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(1234567890L))).hasSize(6);
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step(1234567890L))).startsWith("00");
    }

    @Test
    @DisplayName("aceita o código do passo anterior e do seguinte, e recusa o de dois atrás")
    void toleratesOneStepOfDrift() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = TotpGenerator.stepAt(now);
        String secret = TotpGenerator.newSecret();

        assertThat(TotpGenerator.matchingStep(secret, TotpGenerator.codeAt(secret, current), now))
                .isEqualTo(current);
        // relógio do celular atrasado / código digitado no fim da janela
        assertThat(TotpGenerator.matchingStep(secret, TotpGenerator.codeAt(secret, current - 1), now))
                .isEqualTo(current - 1);
        assertThat(TotpGenerator.matchingStep(secret, TotpGenerator.codeAt(secret, current + 1), now))
                .isEqualTo(current + 1);
        // fora da tolerância: 60 segundos velho não entra
        assertThat(TotpGenerator.matchingStep(secret, TotpGenerator.codeAt(secret, current - 2), now))
                .isNull();
    }

    @Test
    @DisplayName("devolve o passo, e não um sim: é o que permite barrar o replay")
    void reportsTheStepSoCallersCanBlockReplay() {
        Instant now = Instant.ofEpochSecond(1_700_000_030L);
        String secret = TotpGenerator.newSecret();
        long step = TotpGenerator.stepAt(now);

        Long matched = TotpGenerator.matchingStep(secret, TotpGenerator.codeAt(secret, step), now);

        assertThat(matched).isEqualTo(step);
    }

    @Test
    @DisplayName("recusa o que nem tem forma de código, sem calcular HMAC nenhum")
    void rejectsMalformedCodes() {
        String secret = TotpGenerator.newSecret();
        Instant now = Instant.now();

        assertThat(TotpGenerator.matchingStep(secret, null, now)).isNull();
        assertThat(TotpGenerator.matchingStep(secret, "", now)).isNull();
        assertThat(TotpGenerator.matchingStep(secret, "12345", now)).isNull();
        assertThat(TotpGenerator.matchingStep(secret, "1234567", now)).isNull();
        assertThat(TotpGenerator.matchingStep(secret, "12345a", now)).isNull();
    }

    @Test
    @DisplayName("espaço no meio do código não reprova quem copiou e colou")
    void ignoresWhitespaceInTypedCodes() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String secret = TotpGenerator.newSecret();
        String code = TotpGenerator.codeAt(secret, TotpGenerator.stepAt(now));

        // vários autenticadores mostram "123 456"
        String spaced = code.substring(0, 3) + " " + code.substring(3);

        assertThat(TotpGenerator.matchingStep(secret, spaced, now)).isNotNull();
    }

    @Test
    @DisplayName("cada segredo novo é diferente e tem 160 bits")
    void generatesFreshSecrets() {
        String first = TotpGenerator.newSecret();
        String second = TotpGenerator.newSecret();

        assertThat(first).isNotEqualTo(second);
        // 20 bytes viram 32 caracteres em Base32
        assertThat(first).hasSize(32);
        assertThat(TotpGenerator.fromBase32(first)).hasSize(20);
    }

    @Test
    @DisplayName("Base32 fecha a volta byte a byte")
    void base32RoundTrips() {
        byte[] original = {0, 1, 2, 3, 4, 5, 6, 7, (byte) 200, (byte) 255};

        assertThat(TotpGenerator.fromBase32(TotpGenerator.toBase32(original)))
                .startsWith(original);
    }

    @Test
    @DisplayName("segredo com caractere fora do alfabeto estoura em vez de gerar código errado")
    void rejectsInvalidSecret() {
        // silenciar aqui produziria um código que nunca confere, e o usuário
        // ficaria olhando "código inválido" sem saber por quê
        assertThatThrownBy(() -> TotpGenerator.fromBase32("ABC1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a URI do QR leva emissor, conta e os parâmetros que o app precisa")
    void buildsOtpauthUri() {
        String uri = TotpGenerator.otpauthUri("Economize!", "ana@example.com", "ABCDEFGH");

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=ABCDEFGH");
        assertThat(uri).contains("issuer=Economize%21");
        assertThat(uri).contains("algorithm=SHA1");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
        // "+" de encoder de formulário viraria um "+" literal no nome exibido
        assertThat(uri).doesNotContain("+");
    }

    private static long step(long epochSeconds) {
        return TotpGenerator.stepAt(Instant.ofEpochSecond(epochSeconds));
    }
}
