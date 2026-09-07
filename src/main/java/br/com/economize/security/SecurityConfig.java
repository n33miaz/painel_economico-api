package br.com.economize.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtAuthenticationFilter jwtFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // Usa o CorsConfigurationSource do CorsConfig para liberar o
                // preflight OPTIONS antes da checagem de autenticação
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        // Rotas públicas
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        // Versão mínima do app: o cliente pergunta ANTES de ter
                        // token — e o app antigo pode nem conseguir um
                        .pathMatchers("/api/v1/app/version").permitAll()
                        // `/swagger-ui.html` é a PORTA de entrada e não casa com
                        // `/swagger-ui/**` (que exige a barra): sem ela na lista,
                        // quem abria a documentação levava 401 antes de qualquer
                        // redirecionamento acontecer
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/webjars/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        // Qualquer outra rota exige token
                        .anyExchange().authenticated())
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}