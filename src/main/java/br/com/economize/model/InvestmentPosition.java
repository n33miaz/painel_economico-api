package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma posição de investimento do usuário — o ESTADO ("quanto tenho, em quê,
 * valendo quanto hoje"), e não o lançamento que a criou. A aplicação e o
 * resgate continuam no extrato ({@link BankTransaction}); aqui fica o saldo
 * que eles produziram e que o provedor reavalia a cada sincronização.
 *
 * <p>As três origens convivem na mesma tabela porque a pergunta do usuário é
 * uma só. O que muda entre elas é quem pode editar: a posição do CONECTOR é
 * substituída pelo sync e não aceita edição; a MANUAL é do usuário e aceita
 * tudo; a STATEMENT está reservada para a derivação do extrato (ver V24).
 */
@Entity
@Table(name = "investment_positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentPosition {

    public enum Source {CONNECTOR, STATEMENT, MANUAL}

    /**
     * Vocabulário do PRODUTO, não do provedor. O Pluggy fala MUTUAL_FUND e
     * SECURITY; o usuário fala fundo e previdência. A tradução acontece no
     * mapeamento do sync, uma vez, e o banco guarda a palavra final.
     */
    public enum Type {FIXED_INCOME, TREASURY, FUND, EQUITY, ETF, CRYPTO, PENSION, OTHER}

    /**
     * O que liga a posição ao indicador que o usuário quer acompanhar: quem
     * tem CDB acompanha o CDI, quem tem Tesouro IPCA+ acompanha a inflação.
     * NONE é a resposta honesta para ação e ETF, que não têm indexador.
     */
    public enum Indexer {CDI, SELIC, IPCA, PREFIXADO, USD, NONE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Source source;

    // id da posição no provedor; alvo do upsert de cada sync. Nulo na manual
    @Column(name = "provider_position_id", length = 80)
    private String providerPositionId;

    // Referências soltas (UUID, sem @ManyToOne), como em ConnectorAccount: o
    // vínculo é informativo e some quando o usuário desvincula a instituição,
    // sem que a posição deixe de existir
    @Column(name = "pluggy_item_id")
    private UUID pluggyItemId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(length = 160)
    private String institution;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(length = 32)
    private String subtype;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Indexer indexer;

    // texto de apresentação ("110% CDI", "IPCA + 6,20%") — ninguém o parseia
    @Column(length = 40)
    private String rate;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 6)
    private BigDecimal unitPrice;

    @Column(name = "invested_amount", precision = 19, scale = 4)
    private BigDecimal investedAmount;

    /**
     * Valor atual. NULO quer dizer "não sei", nunca zero: a posição manual sem
     * cotação fica assim e a API declara que falta cotação em vez de inventar.
     */
    @Column(name = "current_value", precision = 19, scale = 4)
    private BigDecimal currentValue;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    /**
     * Data a que o saldo se refere. A posição que sumiu do provedor não é
     * apagada — só para de receber esta data, e é assim que fica marcada como
     * desatualizada.
     */
    @Column(name = "position_date")
    private LocalDate positionDate;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public boolean isManual() {
        return source == Source.MANUAL;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.currency == null || this.currency.isBlank()) this.currency = "BRL";
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
