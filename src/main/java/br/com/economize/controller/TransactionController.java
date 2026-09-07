package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.dto.statement.ReviewApplyRequest;
import br.com.economize.dto.statement.ReviewGroupResponse;
import br.com.economize.dto.statement.UpdateIgnoredRequest;
import br.com.economize.dto.statement.UpdateInternalTransferRequest;
import br.com.economize.dto.statement.UpdateTransactionAliasRequest;
import br.com.economize.model.BankTransaction;
import br.com.economize.service.DuplicateTransactionService;
import br.com.economize.service.InternalTransferService;
import br.com.economize.service.TransactionAliasService;
import br.com.economize.service.TransactionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transações", description = "Listagem com filtros, apelido e fluxo de revisão de categorização")
public class TransactionController {

    private final TransactionReviewService reviewService;
    private final TransactionAliasService aliasService;
    private final InternalTransferService internalTransferService;
    private final DuplicateTransactionService duplicateService;

    @Operation(summary = "Listar transações bancárias",
            description = "Período opcional por `month=YYYY-MM` OU pelo par `start`/`end` em datas ISO "
                    + "`YYYY-MM-DD` inclusivas (mesma janela ancorada da análise — EC-092); sem período, "
                    + "devolve o histórico. Filtros adicionais: status de revisão e categoria. "
                    + "Mês e janela juntos, janela pela metade, `end` antes de `start` ou janela acima de "
                    + "366 dias respondem 400 (ProblemDetail). `description` já vem com o apelido quando "
                    + "existe; `originalDescription` traz sempre o descritivo do banco. "
                    + "`accountId` (EC-113) recorta por ORIGEM — é como se pede \"o que gastei NO CARTÃO "
                    + "neste mês\"; os ids saem de GET /api/v1/accounts. Cada linha devolve o `accountId` "
                    + "dela, nulo quando a origem não é conhecida (histórico e upload manual de arquivo).")
    @GetMapping
    public Mono<List<BankTransactionResponse>> list(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) BankTransaction.ReviewStatus status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID accountId) {
        AnalysisWindow window = AnalysisWindow.resolve(month, start, end);
        return Mono.fromCallable(() -> reviewService
                        .listTransactions(email, window, status, categoryId, accountId).stream()
                        .map(BankTransactionResponse::from)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Renomear transação (apelido)",
            description = "Troca o rótulo de APRESENTAÇÃO da transação: `description` passa a devolver o "
                    + "apelido em toda listagem, enquanto `originalDescription` continua trazendo o "
                    + "descritivo do banco. `displayAlias` nulo ou em branco limpa o apelido. Máximo de 80 "
                    + "caracteres (400 com ProblemDetail acima disso). Transação inexistente OU de outro "
                    + "usuário responde 404 — o dono é filtro da consulta, não checagem posterior. "
                    + "O apelido não altera categorização, regras aprendidas, recorrência nem dedupe.")
    @PatchMapping("/{id}/alias")
    public Mono<BankTransactionResponse> updateAlias(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionAliasRequest request) {
        return Mono.fromCallable(() -> aliasService.rename(email, id, request.displayAlias()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(BankTransactionResponse::from);
    }

    @Operation(summary = "Fila de revisão agrupada",
            description = "Transações aguardando aprovação (sugeridas) ou ajuda (sem categoria), "
                    + "agrupadas por estabelecimento normalizado. uploadId restringe a uma importação.")
    @GetMapping("/review")
    public Mono<List<ReviewGroupResponse>> reviewQueue(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) UUID uploadId) {
        return Mono.fromCallable(() -> ReviewGroupResponse.groupsFrom(reviewService.reviewQueue(email, uploadId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Quantas transações aguardam revisão",
            description = "Só o número. A Home e a Análise mostravam \"N transações esperando você\" baixando "
                    + "a fila inteira agrupada (92 KB para 1.656 pendentes) a cada abertura, quando a única "
                    + "coisa desenhada era a contagem.")
    @GetMapping("/review/count")
    public Mono<Map<String, Object>> reviewCount(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> Map.<String, Object>of("count", reviewService.pendingCount(email)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Dizer se a linha é dinheiro seu trocando de bolso",
            description = "Movimentação entre contas do próprio titular não é receita nem despesa: sai das "
                    + "somas da Análise e da Casa. Até aqui a marca só era posta na importação, e só para a "
                    + "perna de cartão que o conector reconhecia — não havia como o usuário dizer que um Pix "
                    + "para si mesmo é dele. Decisão do usuário: a varredura automática não a desfaz.")
    @PatchMapping("/{id}/internal")
    public Mono<BankTransactionResponse> setInternal(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInternalTransferRequest request) {
        return Mono.fromCallable(() -> BankTransactionResponse.from(
                        internalTransferService.setInternal(email, id, request.internalTransfer())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Varrer o histórico procurando transferências para si mesmo",
            description = "Marca como movimentação própria as transferências em que a contraparte é o "
                    + "próprio titular (o extrato escreve o nome dela). Medido no extrato real: sem isto, a "
                    + "renda da casa em agosto apareceu R$ 2.191 acima da renda de verdade. Não desmarca "
                    + "nada — rodar duas vezes é seguro.")
    @PostMapping("/reconcile-internal")
    public Mono<InternalTransferService.Outcome> reconcileInternal(
            @AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> internalTransferService.reconcileByOwnName(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Ignorar (ou trazer de volta) uma linha do extrato",
            description = "Linha ignorada sai de toda soma — análise, previsão e Casa — mas continua no "
                    + "extrato com selo, e volta com um toque. Serve para a duplicata que entrou pela "
                    + "conexão bancária E por um arquivo. Não é apagar de propósito: apagar é irreversível, "
                    + "e reimportar o arquivo não desfaz (o upload é idempotente por hash).")
    @PatchMapping("/{id}/ignored")
    public Mono<BankTransactionResponse> setIgnored(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIgnoredRequest request) {
        return Mono.fromCallable(() -> BankTransactionResponse.from(
                        duplicateService.setIgnored(email, id, request.ignored())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Procurar lançamentos duplicados entre as duas fontes",
            description = "Pareia mesmo valor, até um dia de diferença, um lado COM conta de origem "
                    + "(conexão bancária) e outro SEM (arquivo importado) — a assinatura da mesma transação "
                    + "que entrou por duas portas. Medido no extrato real: 18 pares, R$ 4.855. "
                    + "`dryRun=true` (padrão) só relata; o critério é heurístico e a conta é do usuário, "
                    + "então nada é marcado sem ele pedir. Quando marca, o lado do arquivo é o descartado — "
                    + "o outro carrega a instituição.")
    @PostMapping("/duplicates/sweep")
    public Mono<DuplicateTransactionService.Outcome> sweepDuplicates(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return Mono.fromCallable(() -> duplicateService.sweep(email, dryRun))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Reexaminar a fila com o motor atual",
            description = "Roda o motor de categorização de novo sobre o que ainda espera revisão. "
                    + "A categorização só acontecia na importação, então melhorar o vocabulário ou "
                    + "aprender uma regra numa correção nunca alcançava o extrato já importado. "
                    + "Não toca em transação CONFIRMED: decisão do usuário não é sobrescrita.")
    @PostMapping("/review/recategorize")
    public Mono<TransactionReviewService.RecategorizeOutcome> recategorize(
            @AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> reviewService.recategorizePending(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Aplicar decisões de revisão em lote",
            description = "Confirma categorias por grupo de transações; por padrão aprende o padrão para as próximas importações.")
    @PatchMapping("/review")
    public Mono<Map<String, Object>> applyReview(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ReviewApplyRequest request) {
        return Mono.fromCallable(() -> reviewService.apply(email, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(outcome -> Map.of(
                        "confirmed", outcome.confirmed(),
                        "rulesSaved", outcome.rulesSaved()));
    }

    @Operation(summary = "Aprovar todas as sugestões pendentes",
            description = "Confirma tudo que o motor sugeriu (não toca as sem categoria). uploadId restringe a uma importação.")
    @PostMapping("/review/confirm-all")
    public Mono<Map<String, Object>> confirmAll(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) UUID uploadId) {
        return Mono.fromCallable(() -> reviewService.confirmAll(email, uploadId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(outcome -> Map.of(
                        "confirmed", outcome.confirmed(),
                        "rulesSaved", outcome.rulesSaved()));
    }
}
