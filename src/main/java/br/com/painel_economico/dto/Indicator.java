package br.com.painel_economico.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
public class Indicator {
    private String code;

    @JsonAlias("codein")
    private String codeIn;

    private String name;

    @JsonAlias("bid")
    private BigDecimal buy;

    @JsonAlias("ask")
    private BigDecimal sell;

    @JsonAlias({ "pctChange", "varBid" })
    private BigDecimal variation;

    private String id;
    private String type;

    public BigDecimal getVariation() {
        return variation != null ? variation.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
}