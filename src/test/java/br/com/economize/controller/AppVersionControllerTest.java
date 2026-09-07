package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.security.AppVersionFilter;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.AppVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O endpoint público de versão e o filtro de 426 na fatia web — inclusive a
 * ordem entre eles: o filtro roda antes do Security, então um app antigo SEM
 * token recebe 426 (atualize) e não 401 (faça login de novo).
 */
@WebFluxTest(AppVersionController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        AppVersionService.class })
class AppVersionControllerTest {

    private static final Pattern NAME = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("GET /app/version é público, cacheável por 5 minutos e anuncia mínima/recente/download")
    void versionEPublicoECacheavel() {
        webTestClient.get()
                .uri("/api/v1/app/version")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .expectBody()
                .jsonPath("$.minVersion").isEqualTo("2.2.0")
                .jsonPath("$.latestVersion").isEqualTo("2.2.0")
                .jsonPath("$.downloadUrl").isEqualTo("https://economize-web.onrender.com/baixar")
                .jsonPath("$.storeUrl").isEmpty()
                .jsonPath("$.message").isEqualTo(AppVersionFilter.DEFAULT_MESSAGE)
                // na fatia não há build-info: a identidade honesta é "dev"
                .jsonPath("$.apiVersion").isEqualTo("dev")
                .jsonPath("$.schemaVersion").isEqualTo(maiorMigrationDoProjeto());
    }

    @Test
    @DisplayName("GET /app/version responde mesmo para o app antigo — é o que ele precisa ler")
    void versionRespondeAoAppAntigo() {
        webTestClient.get()
                .uri("/api/v1/app/version")
                .header(AppVersionFilter.VERSION_HEADER, "0.0.1")
                .header(AppVersionFilter.PLATFORM_HEADER, "android")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("App antigo em qualquer outra rota recebe 426 ANTES da autenticação, com o download no corpo")
    void appAntigoRecebe426AntesDoSecurity() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(AppVersionFilter.VERSION_HEADER, "2.1.9")
                .header(AppVersionFilter.PLATFORM_HEADER, "android")
                .exchange()
                .expectStatus().isEqualTo(426)
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.type").isEqualTo(AppVersionFilter.PROBLEM_TYPE)
                .jsonPath("$.title").isEqualTo("Atualização necessária")
                .jsonPath("$.minVersion").isEqualTo("2.2.0")
                .jsonPath("$.downloadUrl").isEqualTo("https://economize-web.onrender.com/baixar");
    }

    @Test
    @DisplayName("App na versão mínima sem token segue para o Security e recebe o 401 de sempre")
    void appNovoSemTokenRecebe401() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(AppVersionFilter.VERSION_HEADER, "2.2.0")
                .header(AppVersionFilter.PLATFORM_HEADER, "ios")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Web com versão 'antiga' não é barrada: segue para o Security")
    void webAntigaNaoEBarrada() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(AppVersionFilter.VERSION_HEADER, "1.0.0")
                .header(AppVersionFilter.PLATFORM_HEADER, "web")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Preflight do navegador anuncia os cabeçalhos de versão como permitidos")
    void preflightLiberaOsCabecalhosDeVersao() {
        // URL absoluta: o processador de CORS exige esquema na requisição para
        // compará-la com a Origin; o servidor mock do WebTestClient não o põe
        // sozinho, e o preflight viraria 403 por "origem malformada"
        webTestClient.options()
                .uri("http://localhost/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        AppVersionFilter.VERSION_HEADER + ", " + AppVersionFilter.PLATFORM_HEADER)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, allowed ->
                        assertThat(allowed.toLowerCase())
                                .contains("x-app-version")
                                .contains("x-app-platform"));
    }

    private static String maiorMigrationDoProjeto() {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return "V" + files.map(p -> NAME.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
