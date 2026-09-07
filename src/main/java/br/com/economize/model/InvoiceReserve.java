package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Dinheiro que o dono já separou para pagar uma fatura específica.
 *
 * <p>Não é lançamento: nada saiu da conta. É a declaração de que uma parte do
 * saldo não está disponível porque tem destino certo — o que muda a leitura de
 * "quanto eu tenho" sem mexer no extrato, que continua espelhando o banco.
 *
 * <p>A reserva vive presa a um CICLO ({@code cardAccount} + {@code reference}),
 * não ao cartão: "reservei para a fatura" só significa alguma coisa junto de
 * qual fatura.
 */
@Entity
@Table(name = "invoice_reserves", uniqueConstraints = {
        @UniqueConstraint(name = "uq_invoice_reserves_card_reference",
                columnNames = {"card_account_id", "reference"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceReserve {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_account_id", nullable = false)
    private ConnectorAccount cardAccount;

    /** O mês em que a fatura fecha, "2026-09" — o mesmo texto que o app pede. */
    @Column(nullable = false, length = 7)
    private String reference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Onde o dinheiro está parado. Nulo quando o dono separou fora do que o
     * sistema enxerga, ou quando a conta foi desconectada depois.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_in_account_id")
    private ConnectorAccount heldInAccount;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
