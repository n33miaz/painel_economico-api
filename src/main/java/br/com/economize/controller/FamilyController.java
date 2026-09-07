package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.family.FamilyAnalyticsResponse;
import br.com.economize.dto.family.FamilyRequests;
import br.com.economize.dto.family.FamilyResponses;
import br.com.economize.dto.family.FamilyTransactionResponse;
import br.com.economize.service.family.FamilyAnalyticsService;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.family.FamilyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Grupo familiar (EC-149): a casa, o convite e a visão compartilhada.
 *
 * <p>Nenhuma rota recebe id de grupo: a casa é sempre a do usuário do token.
 * Sem casa, tudo responde 404 — exceto criar e entrar, que são justamente o
 * caminho para ter uma.
 */
@RestController
@RequestMapping("/api/v1/family")
@RequiredArgsConstructor
@Tag(name = "Família", description = "Grupo familiar: casa, convite por código, o que cada membro compartilha e a visão em conjunto")
public class FamilyController {

    private final FamilyService familyService;
    private final FamilyAnalyticsService familyAnalyticsService;
    private final FamilyTransferService familyTransferService;

    @Operation(summary = "Minha casa",
            description = "Grupo, membros (com o que cada um compartilha), os meus parâmetros de "
                    + "compartilhamento e o convite vivo, se houver — SEM o código, que só aparece na "
                    + "emissão. Sem casa responde 404.")
    @GetMapping
    public Mono<FamilyResponses.FamilyResponse> get(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> familyService.get(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Criar a casa",
            description = "Quem cria é o OWNER e já entra compartilhando TOTALS. Sem nome, a casa se chama "
                    + "\"Casa\". Quem já pertence a uma casa recebe 409 — um usuário tem no máximo uma.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FamilyResponses.FamilyResponse> create(@AuthenticationPrincipal String email,
                                                       @RequestBody(required = false)
                                                       @Valid FamilyRequests.CreateFamily request) {
        return Mono.fromCallable(() -> familyService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Renomear a casa", description = "Só o OWNER.")
    @PatchMapping
    public Mono<FamilyResponses.FamilyResponse> rename(@AuthenticationPrincipal String email,
                                                       @Valid @RequestBody FamilyRequests.UpdateFamily request) {
        return Mono.fromCallable(() -> familyService.rename(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desfazer a casa",
            description = "Só o OWNER. Apaga grupo, membros, convites e os parâmetros de compartilhamento "
                    + "de todos — a casa deixa de existir para todo mundo.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal String email) {
        return Mono.fromRunnable(() -> familyService.delete(email))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Emitir convite",
            description = "Só o OWNER. Código de 8 caracteres, válido por 7 dias, de uso único; um convite "
                    + "vivo por casa — emitir de novo invalida o anterior. Esta é a ÚNICA resposta em que "
                    + "o código aparece: o banco guarda só o hash.")
    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FamilyResponses.InviteInfo> issueInvite(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> familyService.issueInvite(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Entrar com um código",
            description = "Maiúsculas, espaços e hífens são normalizados. Código inválido, vencido ou já "
                    + "usado responde o MESMO 404 — a rota não diz qual dos três. Quem já pertence a uma "
                    + "casa recebe 409. Rota no balde caro do rate limit (10/min).")
    @PostMapping("/join")
    public Mono<FamilyResponses.FamilyResponse> join(@AuthenticationPrincipal String email,
                                                     @Valid @RequestBody FamilyRequests.JoinFamily request) {
        return Mono.fromCallable(() -> familyService.join(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Sair ou remover membro",
            description = "MEMBER remove só a si mesmo (`me` ou o próprio id). OWNER remove qualquer outro "
                    + "e não sai — para o OWNER, o caminho é desfazer a casa. Sair apaga os parâmetros de "
                    + "compartilhamento do membro. Id de membro de outra casa responde 404.")
    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeMember(@AuthenticationPrincipal String email,
                                   @PathVariable String memberId) {
        return Mono.fromRunnable(() -> familyService.removeMember(email, memberId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "O que eu compartilho",
            description = "Substitui os meus parâmetros de uma vez: `shareScope` (NONE, TOTALS ou "
                    + "TRANSACTIONS), categorias ocultas, contas compartilhadas (vazio = todas) e se as "
                    + "linhas sem conta entram. Categoria fora do meu catálogo ou conta que não é minha "
                    + "respondem 400.")
    @PutMapping("/sharing")
    public Mono<FamilyResponses.SharingSettings> updateSharing(@AuthenticationPrincipal String email,
                                                               @Valid @RequestBody FamilyRequests.UpdateSharing request) {
        return Mono.fromCallable(() -> familyService.updateSharing(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Análise da casa num período",
            description = "Um bloco por membro com totais e quebra por categoria — cada um já filtrado "
                    + "pelo que ELE compartilha — e o combinado. Quem escolheu NONE aparece com `totals` "
                    + "nulo. O chamador vê a si mesmo por inteiro. Mesmos parâmetros de janela do "
                    + "/analytics/monthly (`month` OU `start`/`end`; sem nenhum, o mês corrente). "
                    + "`categoryName` viaja na resposta porque a categoria pessoal de outro membro só "
                    + "existe no catálogo dele.")
    @GetMapping("/analytics/monthly")
    public Mono<FamilyAnalyticsResponse> monthly(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        AnalysisWindow window = resolveWindow(month, start, end);
        return Mono.fromCallable(() -> familyAnalyticsService.monthly(email, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Extrato da casa num período",
            description = "As linhas de quem compartilha TRANSACTIONS — e todas as minhas —, com o membro "
                    + "dono de cada uma. Categoria oculta e conta não compartilhada não aparecem. Mesma "
                    + "janela do /analytics/monthly (sem nenhum parâmetro, o mês corrente); `memberId` "
                    + "restringe a um membro e `categoryId` a uma categoria.")
    @GetMapping("/transactions")
    public Mono<List<FamilyTransactionResponse>> transactions(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID categoryId) {
        AnalysisWindow window = resolveWindow(month, start, end);
        return Mono.fromCallable(() -> familyAnalyticsService.transactions(email, window, memberId, categoryId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Descontar o que circulou dentro da casa",
            description = "Varre os MEUS lançamentos e marca as transferências cuja contraparte é outra "
                    + "pessoa da mesma casa — o Pix entre o casal, a mesada, o rateio da conta de luz. "
                    + "Esse dinheiro não é renda da casa: renda da casa é o que entra de fora, e o que "
                    + "circula entre os dois já foi contado quando entrou. A marca vale SÓ para a visão da "
                    + "casa: na minha análise pessoal a linha continua lá, porque o dinheiro entrou mesmo. "
                    + "Cada pessoa roda pela sua conta — a API nunca escreve na conta de outro usuário. "
                    + "Nada é desmarcado, então rodar duas vezes é seguro. `against` diz contra quantos "
                    + "membros houve nome completo para comparar: zero explica um resultado zerado.")
    @PostMapping("/reconcile-transfers")
    public Mono<FamilyTransferService.Outcome> reconcileTransfers(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> familyTransferService.reconcile(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Mesma gramática da análise pessoal; sem parâmetro nenhum, o mês corrente.
     * A listagem da casa também cai no mês corrente (e não no histórico inteiro,
     * como a pessoal): são várias pessoas numa resposta só, e a tela sempre
     * pede com o recorte do chip.
     */
    private static AnalysisWindow resolveWindow(String month, String start, String end) {
        AnalysisWindow requested = AnalysisWindow.resolve(month, start, end);
        return requested != null ? requested : AnalysisWindow.ofMonth(YearMonth.now(ZoneOffset.UTC));
    }
}
