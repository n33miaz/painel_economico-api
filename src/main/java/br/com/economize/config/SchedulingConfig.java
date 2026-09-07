package br.com.economize.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendamento ({@code @Scheduled}) da aplicação. Fica numa classe própria,
 * e não na {@code EconomizeApiApplication}, para poder ser desligado por
 * propriedade: a suíte de testes sobe o contexto inteiro várias vezes, e um
 * agendador vivo ali sairia para a internet buscar feeds no meio dos testes.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "economize.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
