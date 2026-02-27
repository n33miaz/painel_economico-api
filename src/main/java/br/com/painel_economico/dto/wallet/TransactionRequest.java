package br.com.painel_economico.dto.wallet;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransactionRequest {
    private String assetCode;
    private String type; // "BUY" ou "SELL"
    private BigDecimal quantity;
    private BigDecimal priceAtTransaction;
}