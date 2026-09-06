package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.repository.TrustedDeviceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.mail.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Aparelhos que dispensam o segundo fator.
 *
 * <p>A razão de existir: com a V20 sozinha, um fator ativo pede código em TODO
 * login, inclusive no celular do dono, dez vezes por dia — e fator que
 * atrapalha o dono é fator que o dono desliga. Os testes abaixo travam as duas
 * pontas disso: o aparelho conhecido entra sem código, e QUALQUER coisa fora do
 * caso exato (segredo de outra conta, vencido, ausente) volta a pedir.
 */
@DataJpaTest
@DisplayName("Aparelhos de confiança")
class TrustedDeviceServiceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrustedDeviceRepository deviceRepository;

    private TrustedDeviceService service;
    private EmailSender emailSender;
    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        emailSender = mock(EmailSender.class);
        service = new TrustedDeviceService(userRepository, deviceRepository, emailSender);
        ana = userRepository.save(User.builder()
                .name("Ana").email("ana@example.com").password("x").build());
        bruno = userRepository.save(User.builder()
                .name("Bruno").email("bruno@example.com").password("x").build());
        entityManager.flush();
    }

    @Test
    @DisplayName("sem segredo nenhum, o aparelho é desconhecido")
    void noTokenMeansUnknown() {
        assertThat(service.isTrusted(ana, null)).isFalse();
        assertThat(service.isTrusted(ana, hint(null))).isFalse();
        assertThat(service.isTrusted(ana, hint("   "))).isFalse();
    }

    @Test
    @DisplayName("o aparelho lembrado entra sem código")
    void aRememberedDeviceIsTrusted() {
        String token = service.remember(ana, "iPhone da Ana", "200.1.2.3");
        entityManager.flush();

        assertThat(service.isTrusted(ana, hint(token))).isTrue();
    }

    @Test
    @DisplayName("o segredo NUNCA fica guardado — só o hash dele")
    void onlyTheHashIsStored() {
        String token = service.remember(ana, "iPhone da Ana", null);
        entityManager.flush();

        // Um dump do banco não pode virar lista de chaves que pulam o fator
        assertThat(deviceRepository.findAll())
                .extracting(d -> d.getTokenHash())
                .noneMatch(hash -> hash.contains(token));
    }

    @Test
    @DisplayName("segredo de OUTRA conta não abre esta")
    void aTokenFromAnotherAccountIsRejected() {
        String doBruno = service.remember(bruno, "Note do Bruno", null);
        entityManager.flush();

        assertThat(service.isTrusted(ana, hint(doBruno))).isFalse();
    }

    @Test
    @DisplayName("segredo desconhecido é desconhecido, e não erro")
    void anUnknownTokenIsJustUntrusted() {
        assertThat(service.isTrusted(ana, hint("nada-disso"))).isFalse();
    }

    @Test
    @DisplayName("aparelho vencido volta a pedir código")
    void anExpiredDeviceIsNoLongerTrusted() {
        String token = service.remember(ana, "Tablet velho", null);
        entityManager.flush();
        // aparelho esquecido num lugar não pode ser porta aberta para sempre
        var device = deviceRepository.findAll().get(0);
        device.setExpiresAt(OffsetDateTime.now().minusDays(1));
        deviceRepository.save(device);
        entityManager.flush();

        assertThat(service.isTrusted(ana, hint(token))).isFalse();
    }

    @Test
    @DisplayName("usar o aparelho atualiza o último acesso")
    void usingADeviceStampsIt() {
        String token = service.remember(ana, "iPhone da Ana", "200.1.2.3");
        entityManager.flush();
        deviceRepository.findAll().forEach(d -> {
            d.setLastUsedAt(OffsetDateTime.now().minusDays(3));
            deviceRepository.save(d);
        });
        entityManager.flush();

        service.isTrusted(ana, hint(token));
        entityManager.flush();

        assertThat(deviceRepository.findAll().get(0).getLastUsedAt())
                .isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    @DisplayName("rede nova AVISA, mas não barra — quem viaja não é invasor")
    void aNewNetworkWarnsWithoutBlocking() {
        String token = service.remember(ana, "iPhone da Ana", "200.1.2.3");
        entityManager.flush();

        assertThat(service.isTrusted(ana, hint(token, "191.9.9.9"))).isTrue();
        verify(emailSender).sendSecurityAlert(org.mockito.ArgumentMatchers.eq("ana@example.com"),
                org.mockito.ArgumentMatchers.contains("rede diferente"));
    }

    @Test
    @DisplayName("a mesma rede não vira aviso — senão o aviso perde o sentido")
    void theSameNetworkIsQuiet() {
        String token = service.remember(ana, "iPhone da Ana", "200.1.2.3");
        entityManager.flush();

        service.isTrusted(ana, hint(token, "200.1.2.3"));

        verify(emailSender, never()).sendSecurityAlert(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("falha no aviso não derruba um login válido")
    void aFailingAlertNeverBreaksTheLogin() {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp fora do ar"))
                .when(emailSender).sendSecurityAlert(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        String token = service.remember(ana, "iPhone da Ana", "200.1.2.3");
        entityManager.flush();

        // O aviso é acessório: quem estava entrando entra
        assertThat(service.isTrusted(ana, hint(token, "191.9.9.9"))).isTrue();
    }

    @Test
    @DisplayName("a listagem mostra os aparelhos e nenhum segredo")
    void theListingCarriesNoSecret() {
        service.remember(ana, "iPhone da Ana", null);
        service.remember(ana, "Chrome no Windows", null);
        entityManager.flush();

        assertThat(service.list("ana@example.com"))
                .hasSize(2)
                .extracting(d -> d.label())
                .containsExactlyInAnyOrder("iPhone da Ana", "Chrome no Windows");
    }

    @Test
    @DisplayName("esquecer um aparelho faz ele voltar a pedir código")
    void forgettingADeviceRestoresTheChallenge() {
        String token = service.remember(ana, "iPhone da Ana", null);
        entityManager.flush();
        UUID id = deviceRepository.findAll().get(0).getId();

        service.forget("ana@example.com", id);
        entityManager.flush();

        assertThat(service.isTrusted(ana, hint(token))).isFalse();
    }

    @Test
    @DisplayName("esquecer aparelho de outra pessoa é 404")
    void forgettingAForeignDeviceIsNotFound() {
        service.remember(bruno, "Note do Bruno", null);
        entityManager.flush();
        UUID doBruno = deviceRepository.findAll().get(0).getId();

        assertThatThrownBy(() -> service.forget("ana@example.com", doBruno))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("esquecer todos é o botão de 'perdi o celular' — e não toca nos dos outros")
    void forgettingAllOnlyTouchesTheOwner() {
        service.remember(ana, "iPhone da Ana", null);
        service.remember(ana, "Chrome no Windows", null);
        String doBruno = service.remember(bruno, "Note do Bruno", null);
        entityManager.flush();

        service.forgetAll("ana@example.com");
        entityManager.flush();

        assertThat(service.list("ana@example.com")).isEmpty();
        assertThat(service.isTrusted(bruno, hint(doBruno))).isTrue();
    }

    private static TrustedDeviceService.DeviceHint hint(String token) {
        return hint(token, null);
    }

    private static TrustedDeviceService.DeviceHint hint(String token, String ip) {
        return new TrustedDeviceService.DeviceHint(token, "aparelho", ip);
    }
}
