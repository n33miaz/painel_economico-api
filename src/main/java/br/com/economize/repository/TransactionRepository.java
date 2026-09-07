package br.com.economize.repository;

import br.com.economize.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    /** Mesma razão do extrato: o Perfil quer o número, não os lançamentos. */
    long countByUserId(UUID userId);

    List<Transaction> findAllByUserIdOrderByTransactionDateDesc(UUID userId);

    // Dono validado NA CONSULTA, como no resto do catálogo: operação de outro
    // usuário não retorna linha e a rota responde 404. Antes daqui o serviço
    // buscava por id e comparava o e-mail depois, respondendo 403 — o que
    // confirma a existência do recurso alheio para quem tem qualquer token
    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);
}