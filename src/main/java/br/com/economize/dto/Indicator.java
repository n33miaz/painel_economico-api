package br.com.economize.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Data
public class Indicator {

    /**
     * Procedência do preço: true quando ele veio do último snapshot bom
     * (provedor fora do ar ou orçamento diário estourado) em vez de uma cotação
     * viva. Fica FORA do JSON de propósito — o contrato do /all consumido pelo
     * APK publicado não muda — e existe só para a informação atravessar o
     * provedor até o catálogo, que não pode rotular preço velho como LIVE.
     */
    @JsonIgnore
    private boolean stale;
    private String id;
    private String type;
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

    private Double points;

    /**
     * De onde o número veio ("AwesomeAPI", "Frankfurter (BCE)", "CoinGecko",
     * "Brapi"...). Desde que a AwesomeAPI passou a estourar cota com frequência
     * o preço da Home pode sair de fontes diferentes ao longo do dia, e o app
     * mostra "atualizado às 10:32 · fonte" — sem isso o usuário não teria como
     * saber de onde é o dólar que está vendo.
     */
    private String source;

    /**
     * Momento a que a cotação se refere, como a fonte o informa (época da
     * AwesomeAPI, {@code last_updated_at} da CoinGecko, data de referência do
     * BCE). Quando o preço vem do snapshot este é o instante ORIGINAL, e não o
     * da leitura: é justamente o que diz ao usuário quão velho o número é.
     */
    private Instant asOf;

    /**
     * Época em segundos que a AwesomeAPI manda em cada cotação. Só entra
     * (WRITE_ONLY): o provedor a converte em {@link #asOf} e ela nunca sai no
     * JSON, para não duplicar a informação nem mudar o contrato do /all.
     */
    @JsonProperty(value = "timestamp", access = JsonProperty.Access.WRITE_ONLY)
    private String providerTimestamp;

    public BigDecimal getVariation() {
        return variation != null ? variation.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * Cópia marcada como stale. É cópia, e não mutação, porque o original mora
     * no snapshot compartilhado: marcá-lo no lugar contaminaria a mesma
     * instância para todo mundo. Os campos são copiados crus (o getter de
     * variação troca null por zero, e um null precisa continuar null).
     */
    public Indicator staleCopy() {
        Indicator copy = new Indicator();
        copy.id = this.id;
        copy.type = this.type;
        copy.code = this.code;
        copy.codeIn = this.codeIn;
        copy.name = this.name;
        copy.buy = this.buy;
        copy.sell = this.sell;
        copy.variation = this.variation;
        copy.points = this.points;
        copy.source = this.source;
        copy.asOf = this.asOf;
        copy.stale = true;
        return copy;
    }
}
