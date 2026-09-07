package br.com.economize.dto.plan;

import br.com.economize.model.Plan;

import java.math.BigDecimal;
import java.util.List;

/**
 * A oferta de planos para o usuário autenticado. {@code current} é o plano
 * VIGENTE (um PLUS vencido aparece como FREE); {@code checkoutAvailable} falso
 * diz ao app para oferecer "tenho interesse" em vez de "assinar";
 * {@code interestRegistered} evita perguntar de novo a quem já respondeu.
 */
public record PlansResponse(
        Plan current,
        List<PlanOption> plans,
        boolean checkoutAvailable,
        boolean interestRegistered
) {

    public record PlanOption(Plan id, String name, BigDecimal priceMonthly, List<String> features) {
    }
}
