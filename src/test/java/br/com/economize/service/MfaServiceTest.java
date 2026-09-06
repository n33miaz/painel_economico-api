package br.com.economize.service;

import br.com.economize.dto.auth.MfaRecoveryCodesResponse;
import br.com.economize.dto.auth.MfaSetupResponse;
import br.com.economize.dto.auth.MfaStatusResponse;
import br.com.economize.model.User;
import br.com.economize.repository.MfaRecoveryCodeRepository;
import br.com.economize.repository.TrustedDeviceRepository;
import br.com.economize.repository.UserMfaRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.MfaSecretCipher;
import br.com.economize.security.TotpGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O ciclo do segundo fator.
 *
 * <p>Os testes que mais importam aqui são os dois de "não se trancar para
 * fora": cadastro pendente NÃO exige código no login, e um código de
 * recuperação entra mesmo quando o segredo TOTP virou ilegível. Depois deles
 * vêm as duas recusas que dão sentido ao fator — replay do mesmo código e
 * reúso de um código de recuperação já gasto.
 */
@DataJpaTest
@DisplayName("Segundo fator (TOTP)")
class MfaServiceTest {

    private static final String JWT_SECRET = "chave-de-teste-longa-o-bastante-para-derivar-256-bits-aqui";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserMfaRepository mfaRepository;

    @Autowired
    private MfaRecoveryCodeRepository recoveryCodeRepository;

    // Desligar o fator leva junto os aparelhos que só existiam para dispensá-lo
    @Autowired
    private TrustedDeviceRepository deviceRepository;

    private MfaService service;
    private PasswordEncoder encoder;
    private RelogioDeTeste relogio;
    private User ana;

    @BeforeEach
    void setUp() {
        // Encoder de custo mínimo: o teste cria 10 hashes por lote de códigos, e
        // o custo padrão do bcrypt transformaria a suíte num minuto de espera
        encoder = new BCryptPasswordEncoder(4);
        relogio = new RelogioDeTeste(Instant.ofEpochSecond(1_700_000_000L));
        service = new MfaService(userRepository, mfaRepository, recoveryCodeRepository,
                new MfaSecretCipher(JWT_SECRET), deviceRepository, encoder, relogio);
        ana = userRepository.save(User.builder()
                .name("Ana")
                .email("ana@example.com")
                .password(encoder.encode("senha-da-ana"))
                .build());
        entityManager.flush();
    }

    @Test
    @DisplayName("conta sem cadastro nenhum não tem fator")
    void reportsAbsent() {
        MfaStatusResponse status = service.status(ana.getEmail());

        assertThat(status.enabled()).isFalse();
        assertThat(status.pendingConfirmation()).isFalse();
        assertThat(status.recoveryCodesRemaining()).isZero();
    }

    @Test
    @DisplayName("o cadastro começado ainda NÃO vale — é o que impede trancar-se para fora")
    void setupDoesNotEnableTheFactor() {
        MfaSetupResponse setup = service.startSetup(ana.getEmail());

        assertThat(setup.secret()).isNotBlank();
        assertThat(setup.otpauthUri()).contains("ana%40example.com");
        // Se o setup já ativasse, quem errasse a leitura do QR levaria um login
        // pedindo um código que o aparelho dele nunca geraria
        assertThat(service.isEnabledFor(ana)).isFalse();
        MfaStatusResponse status = service.status(ana.getEmail());
        assertThat(status.enabled()).isFalse();
        assertThat(status.pendingConfirmation()).isTrue();
    }

    @Test
    @DisplayName("refazer o setup antes de confirmar troca o segredo")
    void setupCanBeRedoneWhilePending() {
        String first = service.startSetup(ana.getEmail()).secret();
        String second = service.startSetup(ana.getEmail()).secret();

        assertThat(second).isNotEqualTo(first);
        // o código do QR abandonado não pode mais ativar nada
        assertThatThrownBy(() -> service.activate(ana.getEmail(), codeFor(first)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.activate(ana.getEmail(), codeFor(second)).codes()).hasSize(10);
    }

    @Test
    @DisplayName("o primeiro código correto ativa e devolve os códigos de recuperação")
    void activateTurnsItOn() {
        String secret = service.startSetup(ana.getEmail()).secret();

        MfaRecoveryCodesResponse codes = service.activate(ana.getEmail(), codeFor(secret));

        assertThat(service.isEnabledFor(ana)).isTrue();
        assertThat(codes.codes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(codes.codes()).allSatisfy(code -> assertThat(code).hasSize(10));
        assertThat(service.status(ana.getEmail()).recoveryCodesRemaining()).isEqualTo(10);
    }

    @Test
    @DisplayName("código errado não ativa")
    void activateRejectsWrongCode() {
        service.startSetup(ana.getEmail());

        assertThatThrownBy(() -> service.activate(ana.getEmail(), "000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.isEnabledFor(ana)).isFalse();
    }

    @Test
    @DisplayName("ativar sem ter começado o cadastro é erro, não um fator ligado do nada")
    void activateRequiresSetup() {
        assertThatThrownBy(() -> service.activate(ana.getEmail(), "123456"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("com o fator ativo, começar outro cadastro é recusado")
    void setupRefusedWhileActive() {
        String secret = service.startSetup(ana.getEmail()).secret();
        service.activate(ana.getEmail(), codeFor(secret));

        assertThatThrownBy(() -> service.startSetup(ana.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desligue");
    }

    @Test
    @DisplayName("o código do autenticador entra")
    void verifyAcceptsTotp() {
        String secret = activate();

        assertThat(service.verify(ana, codeFor(secret))).isTrue();
    }

    @Test
    @DisplayName("o MESMO código não entra duas vezes — replay barrado")
    void verifyBlocksReplay() {
        String secret = activate();
        String code = codeFor(secret);

        assertThat(service.verify(ana, code)).isTrue();
        // trinta segundos de vida não podem ser trinta segundos de tentativas
        assertThat(service.verify(ana, code)).isFalse();
    }

    @Test
    @DisplayName("um código de recuperação entra e é riscado")
    void verifyAcceptsRecoveryCodeOnce() {
        String secret = service.startSetup(ana.getEmail()).secret();
        List<String> codes = service.activate(ana.getEmail(), codeFor(secret)).codes();

        assertThat(service.verify(ana, codes.get(0))).isTrue();
        assertThat(service.status(ana.getEmail()).recoveryCodesRemaining()).isEqualTo(9);
        // uso único: o papel que vazou já não abre nada
        assertThat(service.verify(ana, codes.get(0))).isFalse();
    }

    @Test
    @DisplayName("hífen e caixa no código de recuperação são cosmética")
    void recoveryCodeIsCaseAndDashInsensitive() {
        String secret = service.startSetup(ana.getEmail()).secret();
        String code = service.activate(ana.getEmail(), codeFor(secret)).codes().get(0);

        String digitado = (code.substring(0, 5) + "-" + code.substring(5)).toLowerCase();

        assertThat(service.verify(ana, digitado)).isTrue();
    }

    @Test
    @DisplayName("com o segredo ilegível, o código de recuperação ainda entra")
    void recoveryCodeSurvivesAKeyRotation() {
        String secret = service.startSetup(ana.getEmail()).secret();
        List<String> codes = service.activate(ana.getEmail(), codeFor(secret)).codes();

        // é o cenário do JWT_SECRET trocado: o TOTP morreu, o papel não
        MfaService depoisDaRotacao = new MfaService(userRepository, mfaRepository, recoveryCodeRepository,
                new MfaSecretCipher(JWT_SECRET + "-outro"), deviceRepository, encoder);

        assertThat(depoisDaRotacao.verify(ana, codeFor(secret))).isFalse();
        assertThat(depoisDaRotacao.verify(ana, codes.get(0))).isTrue();
    }

    @Test
    @DisplayName("fator não ativo não verifica nada")
    void verifyFailsWithoutActiveFactor() {
        assertThat(service.verify(ana, "123456")).isFalse();

        String secret = service.startSetup(ana.getEmail()).secret();
        // pendente também não: o login desta conta ainda nem pede código
        assertThat(service.verify(ana, codeFor(secret))).isFalse();
    }

    @Test
    @DisplayName("gerar um lote novo apaga o anterior")
    void rotatingCodesInvalidatesTheOldBatch() {
        String secret = service.startSetup(ana.getEmail()).secret();
        List<String> antigos = service.activate(ana.getEmail(), codeFor(secret)).codes();

        List<String> novos = service.rotateRecoveryCodes(ana.getEmail()).codes();

        assertThat(novos).hasSize(10).doesNotContainAnyElementsOf(antigos);
        assertThat(service.verify(ana, antigos.get(0))).isFalse();
        assertThat(service.verify(ana, novos.get(0))).isTrue();
    }

    @Test
    @DisplayName("sem fator ativo não há lote a girar")
    void rotatingRequiresAnActiveFactor() {
        assertThatThrownBy(() -> service.rotateRecoveryCodes(ana.getEmail()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("desligar exige a senha e leva os códigos junto")
    void disableRequiresThePassword() {
        activate();

        assertThatThrownBy(() -> service.disable(ana.getEmail(), "senha-errada"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.isEnabledFor(ana)).isTrue();

        service.disable(ana.getEmail(), "senha-da-ana");
        entityManager.flush();
        entityManager.clear();

        assertThat(service.isEnabledFor(ana)).isFalse();
        assertThat(recoveryCodeRepository.countByUserIdAndUsedAtIsNull(ana.getId())).isZero();
    }

    @Test
    @DisplayName("o segredo nunca fica em claro no banco")
    void secretIsStoredEncrypted() {
        String secret = service.startSetup(ana.getEmail()).secret();
        entityManager.flush();

        String stored = mfaRepository.findByUserId(ana.getId()).orElseThrow().getSecretCipher();

        assertThat(stored).doesNotContain(secret).startsWith("v1:");
    }

    /**
     * Ativa o fator e devolve o segredo. O relógio anda um passo depois de
     * ativar porque ativar CONSOME o passo atual — no uso real o login vem
     * minutos depois, e sem andar aqui todo teste de verify esbarraria na
     * proteção de replay em vez de testar o que se propõe.
     */
    private String activate() {
        String secret = service.startSetup(ana.getEmail()).secret();
        service.activate(ana.getEmail(), codeFor(secret));
        relogio.avancarUmPasso();
        return secret;
    }

    private String codeFor(String secret) {
        return TotpGenerator.codeAt(secret, TotpGenerator.stepAt(relogio.instant()));
    }

    /** Relógio que anda quando o teste manda. */
    private static final class RelogioDeTeste extends Clock {

        private Instant agora;

        private RelogioDeTeste(Instant inicio) {
            this.agora = inicio;
        }

        void avancarUmPasso() {
            agora = agora.plusSeconds(TotpGenerator.STEP_SECONDS);
        }

        @Override
        public Instant instant() {
            return agora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
