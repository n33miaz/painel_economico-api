package br.com.economize.repository;

import br.com.economize.model.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    /** Mesma razão do extrato: o Perfil quer o número, não os relatórios. */
    long countByUserId(UUID userId);

    Page<Report> findByUserIdAndPeriodOrderByStartDateDesc(UUID userId, Report.Period period, Pageable pageable);

    Page<Report> findByUserIdOrderByStartDateDesc(UUID userId, Pageable pageable);

    Optional<Report> findByIdAndUserId(UUID id, UUID userId);
}
