package br.com.economize.config;

import br.com.economize.security.AppVersionFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    // Origens vêm de CORS_ALLOWED_ORIGINS (lista separada por vírgula); o default
    // cobre o dev local do Expo (Metro em 8081 e web em 19006).
    @Value("${cors.allowed-origins:http://localhost:8081,http://localhost:19006}")
    private List<String> allowedOrigins;

    // Exposto como bean para o Spring Security aplicar o CORS antes da autenticação;
    // via WebFluxConfigurer o preflight OPTIONS morreria no filtro de JWT.
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Os cabeçalhos de versão do app precisam estar aqui: o navegador só os
        // envia se o preflight os listar, e sem eles o app web seria tratado
        // como cliente sem versão (e barrado no dia em que o bloqueio de
        // legado ligar)
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                AppVersionFilter.VERSION_HEADER, AppVersionFilter.PLATFORM_HEADER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
