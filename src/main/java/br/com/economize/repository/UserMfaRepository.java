package br.com.economize.repository;

import br.com.economize.model.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {

    Optional<UserMfa> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
