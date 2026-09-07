package br.com.economize.repository;

import br.com.economize.model.InvestmentPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentPositionRepository extends JpaRepository<InvestmentPosition, UUID> {

    List<InvestmentPosition> findAllByUserIdOrderByNameAsc(UUID userId);

    // Dono validado NA CONSULTA, como em todo o catálogo: posição de outro
    // usuário não retorna linha e a rota responde 404 — id alheio não pode nem
    // confirmar que existe (o IDOR do EC-037 nasceu de um findById solto)
    Optional<InvestmentPosition> findByIdAndUserId(UUID id, UUID userId);

    // Alvo do upsert de cada sincronização: (dono, origem, id no provedor) —
    // o mesmo triplo do índice único parcial da V24
    Optional<InvestmentPosition> findByUserIdAndSourceAndProviderPositionId(
            UUID userId, InvestmentPosition.Source source, String providerPositionId);

    long countByUserIdAndSource(UUID userId, InvestmentPosition.Source source);
}
