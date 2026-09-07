package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "bank_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransaction {

    // Ciclo de revisão: o motor sugere ou pede ajuda; só o usuário confirma
    public enum ReviewStatus {SUGGESTED, UNCATEGORIZED, CONFIRMED}

    // Quem decidiu a categoria — pesa na confiança e alimenta métricas do motor
    public enum CategorizedBy {USER_RULE, LEARNED_RULE, KEYWORD, FALLBACK, AI, USER}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "transaction_id", nullable = false, length = 100)
    private String transactionId;

    @Column(nullable = false, length = 20)
    private String type; // CREDIT, DEBIT

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    // Apelido do usuário (EC-094): rótulo de apresentação para esta transação.
    // Nunca substitui description — e nunca alimenta motor nenhum
    @Column(name = "display_alias", length = 80)
    private String displayAlias;

    // Legado (enum como string) — mantido para os relatórios até migrarem ao category_id
    @Column(length = 32)
    private String category;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 14)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "categorized_by", length = 12)
    private CategorizedBy categorizedBy;

    // 0.00–1.00 — confiança da atribuição automática; NULL quando decidida pelo usuário
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    // Chave de matching cacheada — agrupa "o mesmo estabelecimento" na revisão
    @Column(name = "normalized_description", length = 160)
    private String normalizedDescription;

    @Column(name = "upload_id")
    private UUID uploadId;

    /**
     * Origem do lançamento (EC-113): qual conta bancária ou cartão de crédito do
     * usuário trouxe esta linha. Como {@code internalTransfer}, o fato só é
     * conhecido na IMPORTAÇÃO — o conector sabe de qual conta puxou; depois de
     * gravada, a linha guardaria apenas type/amount/description e a origem seria
     * irrecuperável.
     *
     * <p>NULO significa "origem não informada", e é o valor CORRETO em dois
     * casos permanentes: o histórico anterior à V16 (não há o que backfillar) e
     * o upload manual de arquivo, que não tem conta de provedor nenhuma. Quem lê
     * nunca deve tratar nulo como erro.
     */
    @Column(name = "account_id")
    private UUID accountId;

    /**
     * Perna de movimentação entre contas do próprio titular (EC-106) — hoje o
     * pagamento de fatura de cartão, dos dois lados. Não é receita nem despesa:
     * o dinheiro só troca de bolso. O sinal e o {@code type} continuam corretos
     * (o saldo fecha); a marca serve para tirar a linha das SOMAS de
     * receita/despesa e para a série de recorrência nascer INTERNAL.
     */
    @Column(name = "internal_transfer", nullable = false)
    private boolean internalTransfer;

    /**
     * A linha não deveria existir — quase sempre a mesma transação que entrou
     * pela conexão bancária E por um arquivo importado (V26).
     *
     * <p>Não confundir com {@link #internalTransfer}: lá o movimento existiu e
     * só não é receita nem despesa; aqui a linha é um fantasma. Ignorada sai de
     * toda soma, continua no extrato com selo, e volta com um toque — apagar
     * seria irreversível, e reimportar o arquivo não desfaz (o upload é
     * idempotente por hash).
     */
    @Column(nullable = false)
    private boolean ignored;

    /** Quem decidiu ignorar: a varredura de duplicatas, ou a pessoa. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ignored_reason", length = 16)
    private IgnoredReason ignoredReason;

    public enum IgnoredReason {
        /** A varredura pareou esta linha com outra igual de outra fonte. */
        DUPLICATE,
        /** Decisão manual — a varredura nunca a desfaz. */
        USER
    }

    @Column(nullable = false)
    private OffsetDateTime date;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /**
     * O texto que a UI mostra: o apelido quando existe, senão o descritivo do
     * banco. A precedência mora aqui, num lugar só, porque ela vale para TODA
     * resposta que devolve a descrição — e para nenhum motor: normalização,
     * regras aprendidas, chave de recorrência e dedupe leem {@code description}
     * diretamente e continuam cegos ao apelido.
     */
    public String displayDescription() {
        return displayAlias != null && !displayAlias.isBlank() ? displayAlias : description;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        // histórico pré-feature e caminhos sem motor entram como confirmados
        if (this.reviewStatus == null) this.reviewStatus = ReviewStatus.CONFIRMED;
    }
}