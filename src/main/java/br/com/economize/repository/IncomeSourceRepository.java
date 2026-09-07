package br.com.economize.repository;

import br.com.economize.model.IncomeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface IncomeSourceRepository extends JpaRepository<IncomeSource, UUID> {

    List<IncomeSource> findAllByUserIdOrderByKindAscNameAsc(UUID userId);

    List<IncomeSource> findAllByUserIdAndActiveTrue(UUID userId);

    Optional<IncomeSource> findByIdAndUserId(UUID id, UUID userId);

    // colisão de cadastro: uma fonte por (usuário, tipo, nome)
    Optional<IncomeSource> findByUserIdAndKindAndName(UUID userId, IncomeSource.Kind kind, String name);

    // a sugestão vinda do motor de recorrência não pode ser recriada a cada
    // varredura: a série já usada é a chave de idempotência
    boolean existsByUserIdAndSeriesId(UUID userId, UUID seriesId);

    /**
     * Todas as séries já transformadas em fonte, numa consulta só. A lista de
     * sugestões perguntava {@code existsByUserIdAndSeriesId} série a série: com
     * 61 séries detectadas eram 61 idas ao banco — e o banco fica em São Paulo
     * enquanto a API roda na Virgínia, então cada ida custa ~120 ms. Medido em
     * produção, {@code GET /income} levava até 4,5 s por causa deste laço.
     */
    @Query("select s.seriesId from IncomeSource s where s.user.id = :userId and s.seriesId is not null")
    Set<UUID> findLinkedSeriesIds(@Param("userId") UUID userId);
}
