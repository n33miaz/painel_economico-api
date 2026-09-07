package br.com.economize.repository;

import br.com.economize.model.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, String> {
}
