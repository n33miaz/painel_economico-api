package br.com.economize.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O cofre do segredo TOTP.
 *
 * <p>Três garantias, e a do meio é a que justifica a classe existir: o segredo
 * volta inteiro; ele está AMARRADO ao dono (mover a linha para outro usuário no
 * banco não entrega o fator de ninguém); e trocar o JWT_SECRET torna o envelope
 * ilegível de forma barulhenta, e não silenciosamente aceito.
 */
@DisplayName("Cofre do segredo do segundo fator")
class MfaSecretCipherTest {

    private static final String JWT_SECRET =
            "uma-chave-de-assinatura-longa-o-bastante-para-hmac-sha256-do-projeto";

    private final MfaSecretCipher cipher = new MfaSecretCipher(JWT_SECRET);

    @Test
    @DisplayName("o segredo volta exatamente como entrou")
    void roundTrips() {
        String owner = UUID.randomUUID().toString();
        String secret = TotpGenerator.newSecret();

        String envelope = cipher.encrypt(secret, owner);

        assertThat(cipher.decrypt(envelope, owner)).isEqualTo(secret);
    }

    @Test
    @DisplayName("o envelope não carrega o segredo em claro")
    void envelopeHidesThePlaintext() {
        String owner = UUID.randomUUID().toString();
        String secret = TotpGenerator.newSecret();

        assertThat(cipher.encrypt(secret, owner)).doesNotContain(secret);
    }

    @Test
    @DisplayName("cifrar duas vezes o mesmo segredo dá envelopes diferentes")
    void usesAFreshNonceEveryTime() {
        String owner = UUID.randomUUID().toString();
        String secret = TotpGenerator.newSecret();

        // Sem IV novo a cada gravação, dois usuários com o mesmo segredo (ou o
        // mesmo usuário antes e depois) teriam cifras idênticas no banco — um
        // vazamento por comparação, sem quebrar nada
        assertThat(cipher.encrypt(secret, owner)).isNotEqualTo(cipher.encrypt(secret, owner));
    }

    @Test
    @DisplayName("envelope movido para outro usuário não abre")
    void refusesAnotherOwner() {
        String secret = TotpGenerator.newSecret();
        String envelope = cipher.encrypt(secret, UUID.randomUUID().toString());

        assertThatThrownBy(() -> cipher.decrypt(envelope, UUID.randomUUID().toString()))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("JWT_SECRET trocado torna o envelope ilegível, e diz isso")
    void refusesAfterKeyRotation() {
        String owner = UUID.randomUUID().toString();
        String envelope = cipher.encrypt(TotpGenerator.newSecret(), owner);

        MfaSecretCipher outro = new MfaSecretCipher(JWT_SECRET + "-rotacionado");

        // é o preço documentado da derivação a partir do jwt.secret: falha
        // fechada e visível, com os códigos de recuperação como saída
        assertThatThrownBy(() -> outro.decrypt(envelope, owner))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("cifra adulterada não decifra pela metade — a GCM recusa")
    void refusesTamperedCiphertext() {
        String owner = UUID.randomUUID().toString();
        String envelope = cipher.encrypt(TotpGenerator.newSecret(), owner);
        String[] parts = envelope.split(":");
        // vira o último caractere da cifra
        String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                + parts[3].substring(0, parts[3].length() - 1)
                + (parts[3].endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> cipher.decrypt(tampered, owner))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }

    @Test
    @DisplayName("formato desconhecido é recusado antes de qualquer tentativa de decifrar")
    void refusesForeignEnvelopes() {
        String owner = UUID.randomUUID().toString();

        assertThatThrownBy(() -> cipher.decrypt("nada disso", owner))
                .isInstanceOf(SecretCipher.Unreadable.class);
        assertThatThrownBy(() -> cipher.decrypt(null, owner))
                .isInstanceOf(SecretCipher.Unreadable.class);
        // envelope do cofre do EC-107 (outra chave-mestra) não é aceito aqui
        assertThatThrownBy(() -> cipher.decrypt("v1:k1:aaaa:bbbb", owner))
                .isInstanceOf(SecretCipher.Unreadable.class);
    }
}
