package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * O que cada plano oferece e quanto custa. Defaults em código, sobrescrevíveis
 * por properties ({@code economize.plans.plus.price-monthly=12.90}) — mesmo
 * esquema do catálogo de ativos e dos feeds: preço e lista de vantagens são
 * texto de oferta, mudam com a estratégia comercial e não pedem deploy.
 *
 * <p>{@code checkoutAvailable} fica falso até existir gateway de pagamento. É
 * ele que faz o app mostrar "tenho interesse" no lugar de "assinar".
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.plans")
public class PlanProperties {

    private Option free = Option.of("Gratuito", BigDecimal.ZERO, List.of(
            "Extratos, carteira e metas",
            "Categorização automática",
            "Assistente com a sua própria chave",
            "Com anúncios"));

    private Option plus = Option.of("Economize! Plus", new BigDecimal("9.90"), List.of(
            "Sem anúncios",
            "Conexão bancária ilimitada",
            "Relatórios em PDF",
            "Prioridade no assistente"));

    /** Existe cobrança? Falso até o gateway existir. */
    private boolean checkoutAvailable = false;

    @Data
    public static class Option {
        private String name;
        private BigDecimal priceMonthly;
        private List<String> features = List.of();

        public static Option of(String name, BigDecimal priceMonthly, List<String> features) {
            Option option = new Option();
            option.setName(name);
            option.setPriceMonthly(priceMonthly);
            option.setFeatures(features);
            return option;
        }
    }
}
