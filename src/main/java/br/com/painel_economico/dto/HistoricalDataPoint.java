package br.com.painel_economico.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class HistoricalDataPoint {
    private String timestamp;
    private BigDecimal high;
}