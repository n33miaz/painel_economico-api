package br.com.painel_economico.repository;

import br.com.painel_economico.model.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {
    List<BankTransaction> findAllByUserIdOrderByDateDesc(UUID userId);

    boolean existsByUserIdAndTransactionId(UUID userId, String transactionId);
}