package br.com.painel_economico.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "asset_code", nullable = false, length = 20)
    private String assetCode;

    @Column(nullable = false, length = 10)
    private String type; // "BUY" ou "SELL"

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price_at_transaction", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAtTransaction;

    @Column(name = "transaction_date", updatable = false)
    private OffsetDateTime transactionDate;

    @PrePersist
    protected void onCreate() {
        this.transactionDate = OffsetDateTime.now();
    }
}