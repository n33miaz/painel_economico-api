package br.com.economize.dto.plan;

import br.com.economize.model.Plan;
import jakarta.validation.constraints.NotNull;

/** "Eu pagaria por este plano." Valor fora do enum responde 400 antes de chegar ao serviço. */
public record PlanInterestRequest(
        @NotNull(message = "plan é obrigatório")
        Plan plan
) {
}
