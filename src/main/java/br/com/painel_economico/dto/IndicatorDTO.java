package br.com.painel_economico.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class IndicatorDTO {
    private String code;
    private String name;
    private BigDecimal buy;
    private BigDecimal sell;
    private BigDecimal variation;

    private String location;
    private BigDecimal points;

    @JsonProperty("pctChange")
    public void setPctChange(BigDecimal pctChange) {
        if (this.variation == null) {
            this.variation = pctChange;
        }
    }
}