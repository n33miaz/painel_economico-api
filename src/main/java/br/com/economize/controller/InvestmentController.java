package br.com.economize.controller;

import br.com.economize.dto.investment.InvestmentRequests;
import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.service.investment.InvestmentMovementService;
import br.com.economize.service.investment.InvestmentProfileService;
import br.com.economize.service.investment.InvestmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Investimentos: posições consolidadas (conector + manual), movimentos
 * derivados do extrato e o perfil de personalização.
 *
 * <p>Toda leitura é do usuário do TOKEN. Id de posição ou interesse de outro
 * usuário responde 404, nunca 403 — o padrão da casa desde o EC-037.
 */
@RestController
@RequestMapping("/api/v1/investments")
@RequiredArgsConstructor
@Tag(name = "Investimentos", description = "Posições consolidadas (conector Pluggy + cadastro manual), "
        + "movimentos de investimento derivados do extrato e perfil de personalização (indicadores, tickers e tópicos)")
public class InvestmentController {

    private final InvestmentService investmentService;
    private final InvestmentMovementService movementService;
    private final InvestmentProfileService profileService;

    @Operation(summary = "Resumo dos investimentos",
            description = "Total aplicado, valor atual, lucro e cortes por tipo, instituição e indexador, mais os "
                    + "movimentos dos últimos 12 meses. currentValue soma só as posições que TÊM valor atual; "
                    + "as sem cotação aparecem em needsQuote para o app completar com o indicador ao vivo — "
                    + "a API nunca inventa um zero.")
    @GetMapping("/summary")
    public Mono<InvestmentResponses.Summary> summary(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> investmentService.summary(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Listar posições",
            description = "Todas as posições do usuário, de qualquer origem (CONNECTOR, STATEMENT, MANUAL), em ordem "
                    + "alfabética. stale=true marca a posição do conector sem atualização há mais de 7 dias; "
                    + "editable=true só na manual.")
    @GetMapping("/positions")
    public Mono<List<InvestmentResponses.PositionItem>> positions(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> investmentService.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Cadastrar posição manual",
            description = "Para o que nenhum conector alcança (ETF em corretora no exterior, CDB fora do Open "
                    + "Finance). Obrigatórios: name e type (FIXED_INCOME, TREASURY, FUND, EQUITY, ETF, CRYPTO, "
                    + "PENSION, OTHER). code é normalizado para maiúsculas; currency default BRL. currentValue "
                    + "ausente fica nulo e a posição entra em needsQuote.")
    @PostMapping("/positions")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<InvestmentResponses.PositionItem> create(@AuthenticationPrincipal String email,
                                                         @Valid @RequestBody InvestmentRequests.CreatePosition request) {
        return Mono.fromCallable(() -> investmentService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Editar posição manual",
            description = "Altera só os campos enviados. Posição do conector responde 400: os dados dela vêm do "
                    + "provedor e seriam sobrescritos na próxima sincronização. Id de outro usuário responde 404.")
    @PatchMapping("/positions/{id}")
    public Mono<InvestmentResponses.PositionItem> update(@AuthenticationPrincipal String email,
                                                         @PathVariable UUID id,
                                                         @Valid @RequestBody InvestmentRequests.UpdatePosition request) {
        return Mono.fromCallable(() -> investmentService.update(email, id, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Remover posição",
            description = "Qualquer origem — é assim que o usuário se livra de uma posição do conector que sumiu do "
                    + "provedor e ficou desatualizada. Se ela ainda existir lá, a próxima sincronização a recria.")
    @DeleteMapping("/positions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal String email, @PathVariable UUID id) {
        return Mono.fromRunnable(() -> investmentService.delete(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Movimentos de investimento no extrato",
            description = "Lançamentos de bank_transactions lidos como aplicação (APPLY), resgate (REDEEM), rendimento "
                    + "(YIELD) ou ajuste (OTHER), pela categoria Investimentos ou pelo texto. Janela em meses de "
                    + "calendário, o atual incluído (default 12, máximo 120). amount vem com o sinal do extrato; os "
                    + "totais vêm em valor absoluto; netInvested = applied − redeemed.")
    @GetMapping("/movements")
    public Mono<InvestmentResponses.Movements> movements(
            @AuthenticationPrincipal String email,
            @Parameter(description = "Meses de calendário na janela; ausente usa o default da instalação (12)", example = "12")
            @RequestParam(required = false) Integer months) {
        return Mono.fromCallable(() -> movementService.movements(email, months))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Sincronizar posições com o conector",
            description = "Percorre as conexões Pluggy do usuário e faz upsert das posições (GET /investments do "
                    + "provedor). Posição que sumiu do provedor NÃO é apagada — fica com a data antiga e aparece "
                    + "como stale. Com PLUGGY_ENABLED=false responde 503; sem conexão registrada, 400.")
    @PostMapping("/sync")
    public Mono<InvestmentResponses.SyncResult> sync(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> investmentService.sync(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Perfil de personalização",
            description = "O que este usuário acompanha, derivado das posições, dos movimentos do extrato e dos "
                    + "interesses declarados: indicadores em destaque (CDI, SELIC, IPCA, USD…), itens a "
                    + "acompanhar (watch, com source DERIVED ou MANUAL) e tópicos do vocabulário fixo de notícias. "
                    + "Sem nada, isDefault=true e o perfil padrão.")
    @GetMapping("/profile")
    public Mono<InvestmentResponses.Profile> profile(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> profileService.profile(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Declarar interesse",
            description = "kind: RATE, INDEX, CURRENCY, TICKER ou TOPIC; code maiúsculo (TOPIC: slug do vocabulário "
                    + "de notícias); market só para TICKER (US, BR). Idempotente: repetir devolve o existente.")
    @PostMapping("/interests")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<InvestmentResponses.InterestItem> addInterest(@AuthenticationPrincipal String email,
                                                              @Valid @RequestBody InvestmentRequests.CreateInterest request) {
        return Mono.fromCallable(() -> profileService.addInterest(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Remover interesse declarado",
            description = "Só o que foi declarado à mão se remove; o derivado das posições some quando a posição sumir. "
                    + "Interesse inexistente para este usuário responde 404.")
    @DeleteMapping("/interests/{kind}/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeInterest(@AuthenticationPrincipal String email,
                                     @PathVariable String kind,
                                     @PathVariable String code) {
        return Mono.fromRunnable(() -> profileService.removeInterest(email, kind, code))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
