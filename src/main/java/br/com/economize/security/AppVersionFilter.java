package br.com.economize.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Versão mínima do aplicativo — o vínculo entre o app instalado e a API/banco
 * que estão no ar.
 *
 * <p><b>O problema.</b> O APK não atualiza sozinho e a API atualiza a cada
 * deploy. Quando uma migration muda o contrato, o usuário com o app antigo vê
 * erro genérico ("algo deu errado") e não tem como saber que a saída é baixar a
 * versão nova. O pedido do dono foi exatamente este: bloquear rápido e apontar
 * para o download.
 *
 * <p><b>Como funciona.</b> O app manda {@code X-App-Version} (e
 * {@code X-App-Platform}). Versão abaixo da mínima responde <b>426 Upgrade
 * Required</b> com um ProblemDetail que traz a mínima e a URL de download — o
 * app só precisa reconhecer o status e mostrar a tela de atualização. O
 * {@code GET /api/v1/app/version} público diz a mesma coisa de forma proativa.
 *
 * <p><b>Por que ANTES do Spring Security.</b> Um app antigo pode ter um token
 * vencido, ou nem token: se o 426 viesse depois da autenticação, o cliente
 * legado veria 401 e mandaria a pessoa refazer login num app que não vai
 * funcionar de qualquer jeito. Aqui a ordem é logo depois do rate limit — e,
 * pelo mesmo motivo dele, este filtro cuida do CORS da resposta que ele mesmo
 * corta (ver {@link br.com.economize.config.RateLimitFilter}): sem
 * {@code Access-Control-Allow-Origin} o navegador esconde o 426 e o app web
 * enxerga "sem conexão".
 *
 * <p><b>Sem cabeçalho passa</b> por padrão: a base publicada (2.2.0) ainda não
 * manda versão, e bloqueá-la no dia do deploy seria bloquear todo mundo. Quando
 * o dono decidir que 2.2.0 é passado, {@code APP_BLOCK_LEGACY_CLIENTS=true}
 * passa a tratar ausência de cabeçalho como cliente legado. A plataforma
 * {@code web} nunca é barrada por versão — a web vem sempre do último deploy
 * do site, então versão "antiga" ali é só cache de build, não app instalado.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AppVersionFilter implements WebFilter {

    public static final String VERSION_HEADER = "X-App-Version";
    public static final String PLATFORM_HEADER = "X-App-Platform";
    public static final String MDC_VERSION = "appVersion";
    public static final String MDC_PLATFORM = "appPlatform";
    public static final String PROBLEM_TYPE = "https://economize.app/problems/upgrade-required";

    /** Mensagem padrão quando a instalação não define APP_UPDATE_MESSAGE. */
    public static final String DEFAULT_MESSAGE =
            "Há uma nova versão do Economize!. Atualize o aplicativo para continuar.";

    // O que precisa responder mesmo para cliente antigo: a própria consulta de
    // versão, saúde/métricas e a documentação. Preflight é aperto de mão do
    // navegador e nunca carrega os cabeçalhos do app.
    private static final List<String> EXEMPT_EXACT = List.of("/api/v1/app/version", "/actuator",
            "/v3/api-docs", "/swagger-ui.html");
    private static final List<String> EXEMPT_PREFIXES = List.of("/actuator/", "/v3/api-docs/",
            "/swagger-ui/", "/webjars/");

    private final CorsConfigurationSource corsConfigurationSource;
    private final ObjectMapper objectMapper;
    private final SemanticVersion minVersion;
    private final String downloadUrl;
    private final String updateMessage;
    private final boolean blockLegacyClients;

    public AppVersionFilter(CorsConfigurationSource corsConfigurationSource,
                            ObjectMapper objectMapper,
                            @Value("${economize.app.min-version:2.2.0}") String minVersion,
                            @Value("${economize.app.download-url:https://economize-web.onrender.com/baixar}")
                            String downloadUrl,
                            @Value("${economize.app.update-message:" + DEFAULT_MESSAGE + "}") String updateMessage,
                            @Value("${economize.app.block-legacy-clients:false}") boolean blockLegacyClients) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.objectMapper = objectMapper;
        // mínima ilegível derruba o boot: um APP_MIN_VERSION torto que virasse
        // "ninguém é bloqueado" (ou "todo mundo é") só apareceria em produção
        this.minVersion = SemanticVersion.parse(minVersion).orElseThrow(() -> new IllegalStateException(
                "economize.app.min-version não é uma versão MAJOR.MINOR.PATCH: " + minVersion));
        this.downloadUrl = downloadUrl;
        this.updateMessage = updateMessage;
        this.blockLegacyClients = blockLegacyClients;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (CorsUtils.isPreFlightRequest(request) || HttpMethod.OPTIONS.equals(request.getMethod())
                || isExempt(request.getPath().value())) {
            return chain.filter(exchange);
        }

        String version = trimToNull(request.getHeaders().getFirst(VERSION_HEADER));
        String platform = trimToNull(request.getHeaders().getFirst(PLATFORM_HEADER));
        if (platform != null) platform = platform.toLowerCase(Locale.ROOT);

        if (version == null) {
            if (blockLegacyClients) {
                log.info("Requisição sem {} com bloqueio de legado ligado — 426", VERSION_HEADER);
                return upgradeRequired(exchange);
            }
            return chain.filter(exchange);
        }

        Optional<SemanticVersion> reported = SemanticVersion.parse(version);
        if (reported.isEmpty()) {
            // não é uma versão: ninguém é barrado por cabeçalho que não
            // entendemos — o bloqueio existe para orientar app antigo, não
            // para policiar formato
            log.debug("{} ilegível, ignorado: {}", VERSION_HEADER, version);
        } else if (!"web".equals(platform) && reported.get().isOlderThan(minVersion)) {
            log.info("App {} {} abaixo da mínima {} — 426", platform == null ? "?" : platform,
                    reported.get(), minVersion);
            return upgradeRequired(exchange);
        }

        final String mdcVersion = version;
        final String mdcPlatform = platform == null ? "?" : platform;
        return chain.filter(exchange)
                .doFirst(() -> {
                    MDC.put(MDC_VERSION, mdcVersion);
                    MDC.put(MDC_PLATFORM, mdcPlatform);
                })
                .doFinally(signal -> {
                    MDC.remove(MDC_VERSION);
                    MDC.remove(MDC_PLATFORM);
                });
    }

    private Mono<Void> upgradeRequired(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UPGRADE_REQUIRED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // a decisão depende dos cabeçalhos DESTE cliente: um cache no caminho
        // não pode servir o 426 a quem já atualizou
        response.getHeaders().setCacheControl(CacheControl.noStore());
        applyCorsHeaders(exchange);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UPGRADE_REQUIRED, updateMessage);
        problem.setTitle("Atualização necessária");
        problem.setType(URI.create(PROBLEM_TYPE));
        problem.setProperty("minVersion", minVersion.toString());
        problem.setProperty("downloadUrl", downloadUrl);
        problem.setProperty("timestamp", Instant.now());

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            // sem corpo estruturado o status ainda basta para o app agir
            body = ("{\"status\":426,\"title\":\"Atualização necessária\",\"downloadUrl\":\"" + downloadUrl + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Mesmo julgamento de origem que o Security faria, repetido aqui porque a
     * resposta é cortada antes dele — espelho do que o
     * {@link br.com.economize.config.RateLimitFilter} faz no 429, e pelo mesmo
     * motivo: origem não declarada continua sem cabeçalho (não é papel deste
     * filtro afrouxar a política), origem permitida recebe o
     * {@code Access-Control-Allow-Origin} que faz o navegador ENTREGAR o 426 ao
     * app web em vez de convertê-lo em erro de rede.
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

    static boolean isExempt(String path) {
        if (EXEMPT_EXACT.contains(path)) return true;
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
