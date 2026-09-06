package br.com.economize.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Limite de requisições por cliente.
 *
 * <p><b>Por que este filtro cuida de CORS sozinho.</b> Ele roda em
 * {@code HIGHEST_PRECEDENCE + 10}, MUITO antes do {@code WebFilterChainProxy} do
 * Spring Security (ordem -100) — que é quem aplica o
 * {@link CorsConfigurationSource}. Quando o limite estoura e a resposta é
 * curto-circuitada aqui com {@code setComplete()}, a cadeia do Security nunca
 * roda e o 429 sai <b>sem cabeçalho CORS</b>. Para o navegador, resposta sem
 * {@code Access-Control-Allow-Origin} é resposta bloqueada: o axios do app web
 * recebe {@code error.response === undefined}, o mesmo sintoma de servidor
 * dormindo. O cliente então reage exatamente ao contrário do necessário —
 * reenvia a requisição, tenta de novo com backoff e mostra "Sem conexão com o
 * servidor" — o que aprofunda o próprio rate limit numa tempestade
 * auto-alimentada. Por isso o 429 daqui repete o cabeçalho de origem em vez de
 * depender do filtro de CORS que vem depois. Não "simplifique" removendo:
 * enquanto este filtro estiver na frente do Security, quem corta a resposta
 * responde também pelo CORS dela.
 *
 * <p><b>Preflight não consome token.</b> O {@code OPTIONS} de preflight é aperto
 * de mão do navegador, não trabalho da API — e, por não levar
 * {@code Authorization}, cairia no balde POR IP, fazendo cada chamada
 * autenticada da web custar dois tokens em baldes diferentes. Pior: preflight
 * barrado mata a requisição real que viria atrás. Ele passa direto.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements WebFilter {

    // O teste de chave do EC-107 entra no balde caro porque cada chamada vira
    // uma requisição paga ao provedor do usuário. O prefixo é o da rota exata:
    // ler o catálogo e a configuração são leituras locais e continuam baratas.
    //
    // O aceite de convite da casa (EC-149) entra pelo motivo oposto: é barato
    // para o servidor e por isso mesmo seria barato de adivinhar. O código tem
    // 40 bits; a 10 tentativas por minuto a força bruta deixa de ser um plano.
    // Só o /join — criar a casa, ler e configurar seguem no balde padrão.
    //
    // Os dois caminhos de login entram porque sao adivinhaveis por definicao: a
    // senha, e depois dela os SEIS DIGITOS do segundo fator. A 10 tentativas por
    // minuto, varrer um milhao de combinacoes leva quase dois anos — e o codigo
    // vale 30 segundos. Sem o balde caro aqui, o segundo fator seria decorativo.
    private static final Set<String> EXPENSIVE_PREFIXES = Set.of(
            "/api/v1/chat", "/api/v1/reports", "/api/v1/ai/settings/test", "/api/v1/family/join",
            "/api/v1/auth/login");

    private final ConcurrentMap<String, Bucket> standardBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> expensiveBuckets = new ConcurrentHashMap<>();

    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Os tetos por minuto, configuraveis.
     *
     * <p>Sao properties e nao constantes por dois motivos concretos: o operador
     * afina o limite sem esperar um deploy, e a suite de teste — que dispara
     * dezenas de logins do MESMO cliente em segundos — nao precisa de um truque
     * para nao esbarrar no balde. Os padroes sao os valores que valiam antes.
     */
    private final long standardCapacity;
    private final long expensiveCapacity;

    public RateLimitFilter(CorsConfigurationSource corsConfigurationSource,
                           @Value("${economize.rate-limit.standard-per-minute:60}") long standardCapacity,
                           @Value("${economize.rate-limit.expensive-per-minute:10}") long expensiveCapacity) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.standardCapacity = standardCapacity;
        this.expensiveCapacity = expensiveCapacity;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String key = clientKey(exchange);
        boolean expensive = EXPENSIVE_PREFIXES.stream().anyMatch(path::startsWith);

        Bucket bucket = expensive
                ? expensiveBuckets.computeIfAbsent(key, k -> createBucket(expensiveCapacity, Duration.ofMinutes(1)))
                : standardBuckets.computeIfAbsent(key, k -> createBucket(standardCapacity, Duration.ofMinutes(1)));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            return chain.filter(exchange);
        }

        long retrySeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(retrySeconds, 1)));
        applyCorsHeaders(exchange);
        return response.setComplete();
    }

    /**
     * Repete na resposta cortada o mesmo julgamento de origem que o Security
     * faria: origem não declarada em {@code CORS_ALLOWED_ORIGINS} continua sem
     * cabeçalho nenhum (não é papel do rate limit afrouxar a política), e origem
     * permitida recebe o {@code Access-Control-Allow-Origin} que faz o navegador
     * entregar o 429 ao app — que aí trata "muitas requisições" em vez de achar
     * que está offline. O {@code Vary: Origin} evita que um proxy sirva a mesma
     * resposta 429 para outra origem.
     */
    private void applyCorsHeaders(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null) {
            return;
        }
        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(exchange);
        if (configuration == null) {
            return;
        }
        String allowedOrigin = configuration.checkOrigin(origin);
        if (allowedOrigin == null) {
            return;
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        if (Boolean.TRUE.equals(configuration.getAllowCredentials())) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    private Bucket createBucket(long capacity, Duration window) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build();
    }

    private String clientKey(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) return "u:" + auth.substring(7);
        var remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? "ip:" + remoteAddress.getAddress().getHostAddress() : "anon";
    }
}
