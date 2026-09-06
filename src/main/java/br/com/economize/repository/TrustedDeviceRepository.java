package br.com.economize.repository;

import br.com.economize.model.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, UUID> {

    /**
     * O aparelho por trás do segredo apresentado. A busca é pelo HASH, que é
     * único: o segredo em si nunca é guardado nem consultado.
     */
    Optional<TrustedDevice> findByTokenHash(String tokenHash);

    List<TrustedDevice> findAllByUserIdOrderByLastUsedAtDesc(UUID userId);

    /** Dono como filtro: aparelho de outra pessoa responde igual a inexistente. */
    Optional<TrustedDevice> findByIdAndUserId(UUID id, UUID userId);

    void deleteAllByUserId(UUID userId);

    /**
     * Purga oportunista dos vencidos. Sem job agendado no projeto, é na hora de
     * lembrar um aparelho novo que os expirados saem da tabela — do mesmo jeito
     * que os tokens de recuperação de senha.
     */
    void deleteByExpiresAtBefore(OffsetDateTime moment);
}
