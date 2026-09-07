package br.com.economize.controller;

import br.com.economize.dto.account.AccountResponse;
import br.com.economize.dto.account.CreateAccountRequest;
import br.com.economize.dto.account.CardInvoicesResponse;
import br.com.economize.dto.account.UpsertInvoiceReserveRequest;
import br.com.economize.service.CardInvoiceService;
import br.com.economize.service.InvoiceReserveService;
import br.com.economize.service.ConnectorAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Contas e cartões",
        description = "Origem dos lançamentos (EC-113): de qual conta bancária ou cartão de crédito cada "
                + "transação veio, e a fatura de um cartão agrupada por ciclo")
public class AccountController {

    private final ConnectorAccountService accountService;
    private final CardInvoiceService cardInvoiceService;
    private final InvoiceReserveService invoiceReserveService;

    @Operation(summary = "Listar contas e cartões do usuário",
            description = "As origens conhecidas dos lançamentos: contas bancárias e cartões trazidos pelas "
                    + "conexões do usuário. O extrato devolve apenas `accountId` em cada linha — é esta "
                    + "listagem que dá nome, instituição e tipo, para o app carregar uma vez e casar em "
                    + "memória. Lançamento de upload manual de arquivo não tem conta e vem com `accountId` "
                    + "nulo (origem não informada). `linked=false` marca a origem cujo vínculo foi desfeito: "
                    + "o histórico continua, mas nada novo entra por ela.")
    @GetMapping
    public Mono<List<AccountResponse>> list(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> accountService.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Criar uma conta ou cartão à mão",
            description = "Para quem importa extrato em arquivo: cria a origem que o upload vai carimbar "
                    + "(POST /bank-statements/upload?accountId=...). Nasce desvinculada (`linked=false`), "
                    + "porque nada sincroniza nela sozinho. Se depois a mesma instituição for conectada pelo "
                    + "widget do Pluggy, a conexão REAPROVEITA esta origem em vez de duplicá-la.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccountResponse> create(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CreateAccountRequest request) {
        return Mono.fromCallable(() -> AccountResponse.from(accountService.createManual(
                        email, request.name(), request.institution(), request.type(),
                        request.statementClosingDay(), request.statementDueDay())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Faturas de um cartão, agrupadas por ciclo",
            description = "Os lançamentos do cartão recortados por fatura, do ciclo mais recente para o mais "
                    + "antigo. TODA fatura aqui é DERIVADA — o agregador "
                    + "não entrega fatura fechada, só o extrato: `cycleSource=PROVIDER_CLOSING_DAY` quando o "
                    + "ciclo foi recortado pelo dia de fechamento informado pelo provedor, "
                    + "`CALENDAR_MONTH` quando não havia esse metadado e o recorte foi o mês do calendário. "
                    + "`total` é o que o usuário DEVE naquele ciclo (compras menos estornos); `purchasesTotal` "
                    + "e `refundsTotal` abrem essa conta, e `paymentsTotal` é o pagamento de fatura que entrou "
                    + "no cartão — quitação, que não abate o total do ciclo. "
                    + "Ciclo sem nenhum lançamento é omitido. `months` conta faturas FECHADAS e aceita de 1 a "
                    + "24 (default 6); a fatura em aberto vem sempre por cima, sem consumir a janela. Fora da "
                    + "faixa responde 400. Conta inexistente OU de outro usuário responde 404; conta que "
                    + "existe mas não é cartão responde 400.")
    @GetMapping("/{accountId}/invoices")
    public Mono<CardInvoicesResponse> invoices(
            @AuthenticationPrincipal String email,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "6") int months) {
        return Mono.fromCallable(() -> cardInvoiceService.invoices(email, accountId, months))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Separar dinheiro para uma fatura",
            description = "Registra que o dono já tem, parado em algum lugar, o valor desta fatura — o "
                    + "dinheiro NÃO sai da conta e nenhum lançamento é criado: o extrato continua "
                    + "espelhando o banco. A reserva volta dentro da própria fatura em "
                    + "`GET /accounts/{accountId}/invoices`. Uma reserva por ciclo: chamar de novo "
                    + "sobrescreve o valor, que é a operação comum enquanto a fatura em aberto cresce. "
                    + "`heldInAccountId` é opcional (quem separa fora do sistema não informa) e, quando "
                    + "vem, precisa ser conta do próprio usuário. `reference` é o mês em que a fatura "
                    + "FECHA, AAAA-MM. Conta que não é cartão, valor não positivo ou referência fora do "
                    + "formato respondem 400; conta de outro usuário, 404.")
    @PutMapping("/{accountId}/invoices/{reference}/reserve")
    public Mono<CardInvoicesResponse.Reserve> saveReserve(
            @AuthenticationPrincipal String email,
            @PathVariable UUID accountId,
            @PathVariable String reference,
            @Valid @RequestBody UpsertInvoiceReserveRequest request) {
        return Mono.fromCallable(() -> invoiceReserveService.save(email, accountId, reference,
                        request.amount(), request.heldInAccountId(), request.note()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desfazer a reserva de uma fatura",
            description = "O dono gastou o dinheiro em outra coisa, ou a fatura já foi paga de verdade. "
                    + "Idempotente: apagar reserva que não existe responde 204 do mesmo jeito.")
    @DeleteMapping("/{accountId}/invoices/{reference}/reserve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteReserve(
            @AuthenticationPrincipal String email,
            @PathVariable UUID accountId,
            @PathVariable String reference) {
        return Mono.fromRunnable(() -> invoiceReserveService.delete(email, accountId, reference))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
