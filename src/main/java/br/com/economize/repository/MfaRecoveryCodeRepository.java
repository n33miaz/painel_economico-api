package br.com.economize.repository;

import br.com.economize.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    /**
     * Os códigos ainda válidos. O código chega sem dono declarado — vem digitado
     * pelo usuário — e por isso é conferido contra os hashes DESTA conta, um a
     * um: bcrypt não permite buscar pelo hash.
     */
    List<MfaRecoveryCode> findAllByUserIdAndUsedAtIsNull(UUID userId);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    void deleteAllByUserId(UUID userId);
}
