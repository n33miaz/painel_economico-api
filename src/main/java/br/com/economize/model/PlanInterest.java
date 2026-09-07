package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * "Eu pagaria por este plano" — um registro por (usuário, plano), ver V23. O
 * unique está declarado aqui também porque o H2 dos testes monta o schema pelo
 * mapeamento, e é ele que prova a idempotência do registro.
 */
@Entity
@Table(name = "plan_interest", uniqueConstraints = {
        @UniqueConstraint(name = "uq_plan_interest_user_plan", columnNames = {"user_id", "plan"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Plan plan;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
