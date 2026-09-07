package br.com.economize.repository;

import br.com.economize.model.InvestmentInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestmentInterestRepository extends JpaRepository<InvestmentInterest, UUID> {

    List<InvestmentInterest> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    // A chave natural (dono, tipo, código) é a do UNIQUE: é por ela que o POST
    // fica idempotente e o DELETE encontra o que remover — sempre com o dono na
    // cláusula, para o interesse do vizinho nunca aparecer nem sumir
    Optional<InvestmentInterest> findByUserIdAndKindAndCode(UUID userId, InvestmentInterest.Kind kind, String code);
}
