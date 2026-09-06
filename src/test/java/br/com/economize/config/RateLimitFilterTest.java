package br.com.economize.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O 429 deste filtro sai ANTES do Spring Security, que é quem normalmente
 * aplica o CORS. Sem repetir o cabeçalho de origem aqui, o navegador bloqueia a
 * resposta, o app web enxerga "sem resposta" (o mesmo sintoma de servidor
 * dormindo) e reage reenviando a requisição — o rate limit vira "você está
 * offline" e se aprofunda sozinho. Estes testes existem para isso não voltar.
 */
class RateLimitFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8081";
    private static final String FOREIGN_ORIGIN = "https://site-qualquer.example";

    /** Mesma capacidade do balde padrão do filtro. */
    private static final int STANDARD_CAPACITY = 60;

    private RateLimitFilter filter;
    private AtomicInteger chainCalls;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        // Os mesmos tetos do padrao de producao: esta suite existe para
        // travar o COMPORTAMENTO do filtro, e ele precisa ser medido nos
        // numeros que valem no ar
        filter = new RateLimitFilter(corsSource(), 60, 10);
        chainCalls = new AtomicInteger();
        chain = exchange -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("Preflight passa direto e não gasta token do balde")
    void preflightShouldNotConsumeTokens() {
        for (int i = 0; i < 100; i++) {
            ServerWebExchange preflight = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/v1/indicators/all")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));

            filter.filter(preflight, chain).block();

            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, preflight.getResponse().getStatusCode(),
                    "preflight não pode ser barrado: ele mata junto a requisição real que viria atrás");
        }
        assertEquals(100, chainCalls.get());

        // o balde continua cheio: a chamada real gasta o primeiro token agora
        ServerWebExchange real = get(ALLOWED_ORIGIN);
        filter.filter(real, chain).block();

        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                real.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("429 de origem permitida sai COM Access-Control-Allow-Origin")
    void tooManyRequestsShouldCarryCorsHeadersForAllowedOrigin() {
        exhaustBucket();

        ServerWebExchange blocked = get(ALLOWED_ORIGIN);
        filter.filter(blocked, chain).block();
        HttpHeaders headers = blocked.getResponse().getHeaders();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertEquals(ALLOWED_ORIGIN, headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "sem este cabeçalho o navegador esconde o 429 e o app acha que está offline");
        assertTrue(headers.getOrEmpty(HttpHeaders.VARY).contains(HttpHeaders.ORIGIN),
                "cache intermediário não pode servir este 429 para outra origem");
        assertTrue(Integer.parseInt(headers.getFirst(HttpHeaders.RETRY_AFTER)) >= 1,
                "o cliente precisa saber em quantos segundos pode voltar");
        assertEquals(STANDARD_CAPACITY, chainCalls.get(), "requisição barrada não segue para a cadeia");
    }

    @Test
    @DisplayName("429 de origem não declarada continua sem cabeçalho de CORS")
    void tooManyRequestsShouldNotLeakCorsToForeignOrigin() {
        exhaustBucket();

        ServerWebExchange blocked = get(FOREIGN_ORIGIN);
        filter.filter(blocked, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertNull(blocked.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "afrouxar a política de origem não é papel do rate limit");
    }

    @Test
    @DisplayName("Rota cara tem balde próprio e menor, e o 429 dela também leva CORS")
    void expensiveRouteShouldKeepItsOwnBucket() {
        for (int i = 0; i < 10; i++) {
            filter.filter(get(ALLOWED_ORIGIN, "/api/v1/reports"), chain).block();
        }

        ServerWebExchange blocked = get(ALLOWED_ORIGIN, "/api/v1/reports");
        filter.filter(blocked, chain).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertEquals(ALLOWED_ORIGIN,
                blocked.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // a rota barata não foi afetada: são baldes separados
        ServerWebExchange cheap = get(ALLOWED_ORIGIN);
        filter.filter(cheap, chain).block();
        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                cheap.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("O aceite de convite da casa está no balde caro: 10 tentativas e para (EC-149)")
    void familyJoinShouldBeExpensive() {
        for (int i = 0; i < 10; i++) {
            ServerWebExchange attempt = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/family/join").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));
            filter.filter(attempt, chain).block();
            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, attempt.getResponse().getStatusCode());
        }

        ServerWebExchange eleventh = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/family/join").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));
        filter.filter(eleventh, chain).block();

        // O código tem 40 bits; é a taxa, e não o tamanho, que inviabiliza a
        // força bruta. No balde padrão seriam 60 palpites por minuto.
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, eleventh.getResponse().getStatusCode());
        assertEquals(10, chainCalls.get());

        // o resto da família segue no balde padrão, cheio
        ServerWebExchange readFamily = get(ALLOWED_ORIGIN, "/api/v1/family");
        filter.filter(readFamily, chain).block();
        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                readFamily.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    private void exhaustBucket() {
        for (int i = 0; i < STANDARD_CAPACITY; i++) {
            filter.filter(get(ALLOWED_ORIGIN), chain).block();
        }
    }

    private ServerWebExchange get(String origin) {
        return get(origin, "/api/v1/indicators/all");
    }

    private ServerWebExchange get(String origin, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.ORIGIN, origin));
    }

    /** Mesma configuração que o {@link CorsConfig} publica para o Security. */
    private CorsConfigurationSource corsSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
