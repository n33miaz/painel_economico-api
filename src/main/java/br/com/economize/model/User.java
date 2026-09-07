package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * Senha provisoria pendente de troca (V21). Verdadeiro so em conta criada
     * por outra pessoa: enquanto nao for trocada, alguem alem do dono conhece
     * a senha. A marca e do servidor de proposito — ver a migration.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * Plano da conta (V23). FREE e o app mostra anuncios; PLUS e nao mostra —
     * enquanto {@link #isPlus()} disser que sim. Default FREE nos dois lados
     * (builder e banco): e o estado de toda conta que ja existe.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Plan plan = Plan.FREE;

    /** Ate quando o PLUS vale; nulo em FREE ou PLUS sem prazo (concessao manual). */
    @Column(name = "plan_until")
    private OffsetDateTime planUntil;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        // rede para quem construir a entidade fora do builder: a coluna e NOT NULL
        if (this.plan == null) {
            this.plan = Plan.FREE;
        }
    }

    /**
     * PLUS vigente? PLUS sem prazo vale para sempre; com prazo, vale ate ele.
     * Vencido, a conta volta a ver anuncio sem job nenhum — e a coluna continua
     * PLUS para o historico. E daqui que sai o adsEnabled do GET /users/me.
     */
    public boolean isPlus() {
        return plan == Plan.PLUS && (planUntil == null || planUntil.isAfter(OffsetDateTime.now()));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getUsername() {
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}