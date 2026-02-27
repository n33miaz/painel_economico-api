package br.com.painel_economico.dto.wallet;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionResponse {
    private UUID id;
    private String assetCode;
    private String type;
    private BigDecimal quantity;
    private BigDecimal priceAtTransaction;
    private OffsetDateTime transactionDate;
}