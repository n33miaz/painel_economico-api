package br.com.economize.service.provider.fallback;

import br.com.economize.dto.Indicator;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Uma fonte alternativa de cotação para quando a AwesomeAPI falha (429 de
 * cota, timeout, 5xx) ou o orçamento diário dela acabou. Não é um
 * {@code MarketDataProvider}: não entra na orquestração do /all por conta
 * própria — o {@code AwesomeApiProvider} a chama em cascata, e o resultado sai
 * com o {@link #sourceName()} verdadeiro em cada item, porque o app mostra de
 * onde veio o preço.
 */
public interface FallbackQuoteSource {

    /** Rótulo curto para {@code Indicator.source} e para o log ("Frankfurter (BCE)"). */
    String sourceName();

    /** Lista vazia = a fonte respondeu mas não tinha nada útil; erro = falhou. */
    Mono<List<Indicator>> fetch();
}
