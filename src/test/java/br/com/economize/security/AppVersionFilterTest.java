package br.com.economize.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O 426 deste filtro sai ANTES do Spring Security, como o 429 do rate limit —
 * e pelos mesmos motivos precisa carregar o próprio CORS: sem
 * {@code Access-Control-Allow-Origin} o navegador esconde a resposta e o app
 * web enxerga "sem conexão" no lugar de "atualize".
 */
class AppVersionFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8081";
    private static final String FOREIGN_ORIGIN = "https://site-qualquer.example";
    private static final String MIN = "2.3.0";
    private static final String DOWNLOAD = "https://economize-web.onrender.com/baixar";

    // o mesmo builder que o Boot usa: é ele que registra o mixin que ACHATA as
    // propriedades do ProblemDetail (minVersion no topo, e não em "properties")
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private AtomicInteger chainCalls;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        chainCalls = new AtomicInteger();
        chain = exchange -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        };
    }

    private AppVersionFilter filter(boolean blockLegacy) {
        return new AppVersionFilter(corsSource(), objectMapper, MIN, DOWNLOAD,
                "Atualize o Economize! para continuar.", blockLegacy);
    }

    @Test
    @DisplayName("Versão abaixo da mínima responde 426 com ProblemDetail, download e CORS da origem permitida")
    void versaoAntigaRecebe426ComCorpoECors() throws Exception {
        MockServerWebExchange exchange = request("2.2.0", "android", ALLOWED_ORIGIN);

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(chainCalls.get()).as("requisição barrada não segue para a cadeia").isZero();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
        assertThat(headers.getOrEmpty(HttpHeaders.VARY)).contains(HttpHeaders.ORIGIN);
        assertThat(headers.getCacheControl()).contains("no-store");

        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("status").asInt()).isEqualTo(426);
        assertThat(body.get("type").asText()).isEqualTo(AppVersionFilter.PROBLEM_TYPE);
        assertThat(body.get("title").asText()).isEqualTo("Atualização necessária");
        assertThat(body.get("detail").asText()).isEqualTo("Atualize o Economize! para continuar.");
        assertThat(body.get("minVersion").asText()).isEqualTo(MIN);
        assertThat(body.get("downloadUrl").asText()).isEqualTo(DOWNLOAD);
    }

    @Test
    @DisplayName("Versão igual ou acima da mínima passa e registra a versão no MDC durante a cadeia")
    void versaoNovaPassa() {
        AppVersionFilter filter = filter(false);
        // preguiçosa como a DefaultWebFilterChain real (Mono.defer): o próximo
        // filtro só roda na assinatura, depois do doFirst que preenche o MDC
        WebFilterChain mdcChain = exchange -> Mono.fromRunnable(() -> {
            chainCalls.incrementAndGet();
            assertThat(org.slf4j.MDC.get(AppVersionFilter.MDC_VERSION)).isEqualTo("2.3.0");
            assertThat(org.slf4j.MDC.get(AppVersionFilter.MDC_PLATFORM)).isEqualTo("android");
        });

        MockServerWebExchange igual = request("2.3.0", "Android", ALLOWED_ORIGIN);
        filter.filter(igual, mdcChain).block();
        MockServerWebExchange acima = request("2.10.0", "ios", ALLOWED_ORIGIN);
        filter.filter(acima, chain).block();

        assertThat(igual.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(acima.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(chainCalls.get()).isEqualTo(2);
        // o MDC é limpo ao terminar: a thread volta para o pool sem rastro
        assertThat(org.slf4j.MDC.get(AppVersionFilter.MDC_VERSION)).isNull();
    }

    @Test
    @DisplayName("Sem cabeçalho passa por padrão — a base 2.2.0 publicada ainda não manda versão")
    void semCabecalhoPassaPorPadrao() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Com o bloqueio de legado ligado, ausência de cabeçalho é cliente antigo: 426")
    void semCabecalhoBloqueiaQuandoAFlagLiga() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));

        filter(true).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(ALLOWED_ORIGIN);
        assertThat(chainCalls.get()).isZero();
    }

    @Test
    @DisplayName("Plataforma web nunca é barrada por versão menor — a web vem sempre do último deploy")
    void webNaoEBarradaPorVersao() {
        MockServerWebExchange exchange = request("1.0.0", "web", ALLOWED_ORIGIN);

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/app/version", "/actuator/health", "/actuator", "/v3/api-docs",
            "/v3/api-docs/swagger-config", "/swagger-ui/index.html", "/swagger-ui.html", "/webjars/x.js"})
    @DisplayName("Rotas isentas respondem mesmo para o app antigo: consulta de versão, saúde e documentação")
    void rotasIsentasPassam(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(AppVersionFilter.VERSION_HEADER, "0.0.1")
                .header(AppVersionFilter.PLATFORM_HEADER, "android"));

        filter(true).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Preflight e OPTIONS passam: o navegador não manda os cabeçalhos do app no aperto de mão")
    void preflightPassa() {
        MockServerWebExchange preflight = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));
        MockServerWebExchange options = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/v1/users/me"));

        AppVersionFilter filter = filter(true);
        filter.filter(preflight, chain).block();
        filter.filter(options, chain).block();

        assertThat(preflight.getResponse().getStatusCode()).isNull();
        assertThat(options.getResponse().getStatusCode()).isNull();
        assertThat(chainCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cabeçalho ilegível não barra ninguém: o bloqueio orienta app antigo, não policia formato")
    void versaoIlegivelPassa() {
        MockServerWebExchange exchange = request("banana", "android", ALLOWED_ORIGIN);

        filter(true).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("426 para origem não declarada continua sem cabeçalho de CORS")
    void origemEstranhaNaoRecebeCors() {
        MockServerWebExchange exchange = request("1.0.0", "android", FOREIGN_ORIGIN);

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("afrouxar a política de origem não é papel deste filtro").isNull();
    }

    @Test
    @DisplayName("Sem Origin (app nativo) o 426 sai sem CORS e com o mesmo corpo")
    void semOriginTambemBloqueia() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(AppVersionFilter.VERSION_HEADER, "1.0.0"));

        filter(false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"minVersion\":\"" + MIN + "\"");
    }

    @Test
    @DisplayName("Mínima ilegível derruba a subida em vez de virar 'ninguém é bloqueado'")
    void minimaIlegivelDerrubaOBoot() {
        assertThatThrownBy(() -> new AppVersionFilter(corsSource(), objectMapper, "latest", DOWNLOAD, "x", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.app.min-version");
    }

    private MockServerWebExchange request(String version, String platform, String origin) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, origin)
                .header(AppVersionFilter.VERSION_HEADER, version)
                .header(AppVersionFilter.PLATFORM_HEADER, platform));
    }

    /** Mesma configuração que o CorsConfig publica para o Security. */
    private CorsConfigurationSource corsSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
