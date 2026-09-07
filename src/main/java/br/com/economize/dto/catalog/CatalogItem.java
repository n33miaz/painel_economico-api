package br.com.economize.dto.catalog;

import br.com.economize.dto.Indicator;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Item do catálogo paginado. Estende {@link Indicator} de propósito: o app já
 * tipa e renderiza esse shape, então a lista nova reaproveita todos os campos
 * existentes e só acrescenta metadado. Nada é removido nem muda de tipo.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CatalogItem extends Indicator {

    /** Cotação viva do provedor. */
    public static final String QUOTE_LIVE = "LIVE";
    /** Último preço bom conhecido (provedor falhou ou cota esgotada). */
    public static final String QUOTE_STALE = "STALE";
    /** Sem preço: o item aparece no catálogo só com identidade. */
    public static final String QUOTE_UNQUOTED = "UNQUOTED";

    /** Recorte para a UI: acoes, fiis, etfs, bdrs, indices, moedas, cripto. */
    private String segment;

    /**
     * Procedência do preço. UNQUOTED significa que buy/sell vêm nulos — o
     * catálogo entrega identidade sem gastar cota, e a UI decide se mostra
     * traço ou esqueleto.
     */
    private String quoteStatus;

    /** Cópia dos campos herdados a partir da cotação devolvida pelo provedor. */
    public static CatalogItem fromQuote(Indicator source, String segment, String quoteStatus) {
        CatalogItem item = new CatalogItem();
        item.setId(source.getId());
        item.setType(source.getType());
        item.setCode(source.getCode());
        item.setCodeIn(source.getCodeIn());
        item.setName(source.getName());
        item.setBuy(source.getBuy());
        item.setSell(source.getSell());
        item.setVariation(source.getVariation());
        item.setPoints(source.getPoints());
        // procedência e data viajam com o preço: o catálogo mostra o mesmo
        // "atualizado às · fonte" que a Home
        item.setSource(source.getSource());
        item.setAsOf(source.getAsOf());
        item.setSegment(segment);
        item.setQuoteStatus(quoteStatus);
        return item;
    }

    /** Item sem cotação: identidade vinda do catálogo estático. */
    public static CatalogItem withoutQuote(String id, String type, String code, String name, String segment) {
        CatalogItem item = new CatalogItem();
        item.setId(id);
        item.setType(type);
        item.setCode(code);
        item.setName(name);
        item.setSegment(segment);
        item.setQuoteStatus(QUOTE_UNQUOTED);
        return item;
    }
}
