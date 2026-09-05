package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um código de recuperação — a saída de quem perdeu o aparelho do autenticador.
 *
 * <p>Guardado como hash bcrypt, pelo mesmo motivo das senhas: um dump do banco
 * não pode virar lista de chaves de entrada. Uso único, marcado por
 * {@code usedAt}.
 */
@Entity
@Table(name = "mfa_recovery_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
