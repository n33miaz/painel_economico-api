package br.com.economize.config;

import br.com.economize.security.AppVersionFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Versão do app contra o contexto inteiro — o que a fatia web não prova.
 *
 * <p>Aqui existe o build-info gerado pelo plugin (ver pom), então
 * {@code apiVersion} tem que ser a versão do jar, e não "dev"; e o filtro de
 * 426 corre na cadeia real, com o Spring Security montado, para garantir que
 * a ordem entre os dois é a prometida. Mesma anotação de contexto do
 * {@link OpenApiDocumentationTest} de propósito: o Spring reaproveita o
 * contexto já montado em vez de subir outro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Versão do app no contexto inteiro")
class AppVersionEndpointTest {

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("O /app/version anuncia a versão da build e a maior migration, sem token")
    void anunciaVersaoDaBuildEDoSchema() {
        client.get().uri("/api/v1/app/version")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.apiVersion").value(version -> {
                    if ("dev".equals(version)) {
                        throw new AssertionError("com build-info no classpath a versão não pode ser 'dev'");
                    }
                })
                .jsonPath("$.schemaVersion").value(schema -> {
                    if (!String.valueOf(schema).matches("V\\d+")) {
                        throw new AssertionError("schemaVersion fora do padrão Vn: " + schema);
                    }
                })
                .jsonPath("$.minVersion").exists()
                .jsonPath("$.downloadUrl").exists();
    }

    @Test
    @DisplayName("App antigo sem token recebe 426 e não 401 — o filtro corre antes do Security")
    void appAntigoRecebe426NoContextoReal() {
        client.get().uri("/api/v1/users/me")
                .header(AppVersionFilter.VERSION_HEADER, "0.9.0")
                .header(AppVersionFilter.PLATFORM_HEADER, "android")
                .exchange()
                .expectStatus().isEqualTo(426)
                .expectBody()
                .jsonPath("$.downloadUrl").exists()
                .jsonPath("$.minVersion").exists();
    }
}
