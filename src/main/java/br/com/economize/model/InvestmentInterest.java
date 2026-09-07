package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um interesse que o usuário declarou à mão: "quero acompanhar o dólar",
 * "quero notícias de cripto". O perfil de investimentos deriva quase tudo das
 * posições e dos movimentos, mas o que a pessoa acompanha SEM ter na carteira
 * só ela pode dizer — e só isto ela pode desdizer depois.
 */
@Entity
@Table(name = "investment_interests", uniqueConstraints = {
        @UniqueConstraint(name = "uq_investment_interests_user_kind_code",
                columnNames = {"user_id", "kind", "code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentInterest {

    /**
     * RATE (CDI, SELIC), INDEX (IPCA), CURRENCY (USD), TICKER (VT, PETR4) ou
     * TOPIC — um id do vocabulário fixo de notícias, e só dele.
     */
    public enum Kind {RATE, INDEX, CURRENCY, TICKER, TOPIC}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(nullable = false, length = 32)
    private String code;

    // só para TICKER: o mesmo código existe em duas bolsas
    @Column(length = 8)
    private String market;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
