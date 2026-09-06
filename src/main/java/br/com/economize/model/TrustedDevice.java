package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um aparelho que já provou quem é — ver V22.
 *
 * <p>Sem {@code @ToString} pelo mesmo motivo do {@link UserMfa}: o Lombok
 * geraria um texto com o hash do segredo, e hash de segredo em log é rastro de
 * segredo mesmo sem ser o segredo.
 */
@Entity
@Table(name = "trusted_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 do segredo entregue ao aparelho — nunca o segredo. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(length = 120)
    private String label;

    /** Hash do último IP visto, só para o aviso de acesso de lugar novo. */
    @Column(name = "last_ip_hash", length = 64)
    private String lastIpHash;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
