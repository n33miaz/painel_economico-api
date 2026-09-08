package br.com.economize.service;

import br.com.economize.service.event.StatementImportedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dispara a faxina depois de cada importação bem-sucedida, best-effort: o
 * {@code @EventListener} roda síncrono no publisher in-memory, então o método
 * só AGENDA o trabalho no boundedElastic e devolve o controle na hora — o
 * upload não espera, e falha aqui nunca desfaz a importação (que já foi
 * commitada quando o evento é publicado).
 *
 * <p>Este listener substituiu o que só disparava a detecção de recorrência. O
 * motivo é de ordem: a detecção tem de rodar DEPOIS das marcas, senão um Pix do
 * dono para ele mesmo vira "despesa mensal" e "receita mensal" ao mesmo tempo.
 * Duas escutas separadas no mesmo evento não garantiriam essa ordem — as duas
 * agendam em paralelo.
 *
 * <p>Com o profile "rabbit" ativo os eventos vão para o broker em vez do
 * ApplicationEventPublisher e este listener fica mudo — os gatilhos manuais
 * ({@code POST /transactions/reconcile-internal}, {@code /family/reconcile-transfers},
 * {@code /transactions/duplicates/sweep} e {@code /recurrences/detect}) continuam
 * disponíveis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatementHygieneListener {

    private final StatementHygieneService hygieneService;

    @EventListener
    public void onStatementImported(StatementImportedEvent event) {
        // importação duplicada/vazia não traz transação nova — nada a limpar
        if (event.getTransactionsImported() <= 0) return;
        Mono.fromCallable(() -> hygieneService.runFor(event.getUserId()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        resultado -> log.info("Faxina pós-importação concluída, user={}", event.getUserId()),
                        error -> log.warn("Faxina pós-importação falhou (best-effort): {}",
                                error.getMessage()));
    }
}
