package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O segundo fator de uma conta — ver V20.
 *
 * <p>Sem {@code @ToString} de propósito: o Lombok geraria um texto com o
 * envelope cifrado, e envelope cifrado em log é rastro de segredo mesmo sem ser
 * o segredo.
 */
@Entity
@Table(name = "user_mfa")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMfa {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Envelope "v1:jwt-derived:&lt;iv&gt;:&lt;cifra&gt;" — ver MfaSecretCipher. */
    @Column(name = "secret_cipher", nullable = false, length = 512)
    private String secretCipher;

    /** Só vira true depois do primeiro código correto (ver V20). */
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /** Último passo de tempo aceito — é o que barra o replay do mesmo código. */
    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
