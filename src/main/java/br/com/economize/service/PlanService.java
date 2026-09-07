package br.com.economize.service;

import br.com.economize.config.PlanProperties;
import br.com.economize.dto.plan.PlansResponse;
import br.com.economize.model.Plan;
import br.com.economize.model.PlanInterest;
import br.com.economize.model.User;
import br.com.economize.repository.PlanInterestRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Planos da conta (V23): o que existe para oferecer, em qual o usuário está e
 * quem já disse que pagaria pelo pago.
 *
 * <p>Ainda não há cobrança. O que este serviço faz hoje é a parte que não
 * depende dela: anunciar a oferta com o preço/vantagens configurados e
 * registrar interesse — o dado que decide se vale construir o pagamento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final UserRepository userRepository;
    private final PlanInterestRepository interestRepository;
    private final PlanProperties properties;

    public PlansResponse describe(String email) {
        User user = requireUser(email);
        // vigente, e não o gravado: PLUS vencido é FREE para quem pergunta o
        // que oferecer — a coluna continua PLUS para o histórico
        Plan current = user.isPlus() ? Plan.PLUS : Plan.FREE;
        boolean interested = interestRepository.existsByUserIdAndPlan(user.getId(), Plan.PLUS);
        return new PlansResponse(
                current,
                List.of(option(Plan.FREE, properties.getFree()), option(Plan.PLUS, properties.getPlus())),
                properties.isCheckoutAvailable(),
                interested);
    }

    /**
     * Registra o interesse. Idempotente: repetir não cria linha nem falha. O
     * {@code exists} poupa o insert no caso comum; quem garante a unicidade no
     * duplo toque é o unique (user_id, plan) — por isso o saveAndFlush, para a
     * violação estourar AQUI e não no commit, fora do alcance do catch.
     */
    public void registerInterest(String email, Plan plan) {
        User user = requireUser(email);
        if (interestRepository.existsByUserIdAndPlan(user.getId(), plan)) {
            return;
        }
        try {
            interestRepository.saveAndFlush(PlanInterest.builder().user(user).plan(plan).build());
            log.info("Interesse no plano {} registrado para user={}", plan, email);
        } catch (DataIntegrityViolationException race) {
            // dois toques simultâneos: o outro gravou entre o exists e o insert.
            // Mesmo resultado para quem chamou — o interesse está registrado
            log.debug("Interesse no plano {} já registrado por requisição concorrente (user={})", plan, email);
        }
    }

    private static PlansResponse.PlanOption option(Plan plan, PlanProperties.Option option) {
        return new PlansResponse.PlanOption(plan, option.getName(), option.getPriceMonthly(),
                option.getFeatures());
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
