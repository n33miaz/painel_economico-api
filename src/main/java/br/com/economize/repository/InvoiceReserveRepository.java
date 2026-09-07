package br.com.economize.repository;

import br.com.economize.model.InvoiceReserve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceReserveRepository extends JpaRepository<InvoiceReserve, UUID> {

    List<InvoiceReserve> findAllByUserId(UUID userId);

    // A tela de faturas pede um cartão de cada vez e precisa de todos os ciclos
    // dele numa consulta só — a fatura é montada em memória e cada uma procura
    // a sua reserva na lista
    List<InvoiceReserve> findAllByUserIdAndCardAccountId(UUID userId, UUID cardAccountId);

    // A chave natural (cartão, ciclo) é a do UNIQUE: é por ela que o PUT vira
    // upsert e o DELETE encontra o que remover, sempre com o dono na cláusula
    Optional<InvoiceReserve> findByUserIdAndCardAccountIdAndReference(UUID userId, UUID cardAccountId, String reference);
}
