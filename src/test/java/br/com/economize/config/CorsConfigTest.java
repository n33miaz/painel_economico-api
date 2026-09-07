package br.com.economize.config;

import br.com.economize.security.AppVersionFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A política de CORS que o Security aplica, medida no mesmo processador que ele
 * usa. O que este teste trava: os cabeçalhos de versão do app estão na lista
 * permitida — sem isso o navegador nem os envia, e o app web vira "cliente sem
 * versão" no dia em que o bloqueio de legado ligar.
 */
class CorsConfigTest {

    private static final String ORIGIN = "http://localhost:8081";

    private CorsConfigurationSource source() {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of(ORIGIN, "http://localhost:19006"));
        return config.corsConfigurationSource();
    }

    @Test
    @DisplayName("A configuração aceita a origem do dev, o GET e os cabeçalhos de versão do app")
    void politicaAceitaOsCabecalhosDeVersao() {
        CorsConfiguration configuration = source().getCorsConfiguration(
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")));

        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin(ORIGIN)).isEqualTo(ORIGIN);
        assertThat(configuration.checkHttpMethod(HttpMethod.GET)).isNotNull();
        assertThat(configuration.checkHeaders(List.of(AppVersionFilter.VERSION_HEADER,
                AppVersionFilter.PLATFORM_HEADER, HttpHeaders.AUTHORIZATION)))
                .containsExactlyInAnyOrder(AppVersionFilter.VERSION_HEADER, AppVersionFilter.PLATFORM_HEADER,
                        HttpHeaders.AUTHORIZATION);
        assertThat(configuration.checkHeaders(List.of("X-Qualquer-Coisa")))
                .as("cabeçalho fora da lista continua barrado").isNull();
    }

    @Test
    @DisplayName("O preflight com os cabeçalhos de versão é aceito pelo processador padrão e os anuncia")
    void preflightComCabecalhosDeVersaoEAceito() {
        // URL absoluta de propósito: o processador compara esquema/host/porta
        // da requisição com a Origin para saber se é CORS, e exige esquema —
        // um mock com caminho relativo vira "origem malformada" e 403, um
        // falso negativo que não existe no Netty
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "http://localhost:8080/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                AppVersionFilter.VERSION_HEADER + ", " + AppVersionFilter.PLATFORM_HEADER));

        boolean accepted = new DefaultCorsProcessor().process(
                source().getCorsConfiguration(exchange), exchange);

        assertThat(accepted).isTrue();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
        assertThat(String.join(",", headers.getOrEmpty(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).toLowerCase())
                .contains("x-app-version")
                .contains("x-app-platform");
    }
}
