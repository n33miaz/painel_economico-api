package br.com.economize.repository;

import br.com.economize.model.Plan;
import br.com.economize.model.PlanInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PlanInterestRepository extends JpaRepository<PlanInterest, UUID> {

    /** A pergunta do GET /plans: este usuário já disse que pagaria por este plano? */
    boolean existsByUserIdAndPlan(UUID userId, Plan plan);
}
