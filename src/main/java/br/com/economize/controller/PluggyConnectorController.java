package br.com.economize.controller;

import br.com.economize.dto.connector.ConnectTokenResponse;
import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.RegisterPluggyItemRequest;
import br.com.economize.service.connector.OpenFinanceProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rota LEGADA do conector — o contrato do APK 2.2.0 publicado. Continua
 * respondendo exatamente o que respondia, mas não tem mais lógica própria:
 * delega ao mesmo {@link OpenFinanceProvider} da rota neutra
 * {@code /api/v1/connectors}. Clientes novos usam a rota neutra; esta sai
 * quando a versão mínima do app passar da 2.2.0.
 *
 * <p>O que este controller preserva de propósito: o campo {@code owner} no
 * status, e o <b>400</b> com "PLUGGY_ENABLED" quando o conector está
 * desligado — o app publicado lê essa mensagem.
 */
@RestController
@RequestMapping("/api/v1/connectors/pluggy")
@RequiredArgsConstructor
@Tag(name = "Conector Meu Pluggy (legado)", description = "Contrato do APK 2.2.0 — delega ao mesmo provedor de /api/v1/connectors; clientes novos usam a rota neutra")
public class PluggyConnectorController {

    private static final String DISABLED_MESSAGE = "Conector Pluggy desativado — defina PLUGGY_ENABLED=true";

    private final OpenFinanceProvider provider;

    @Operation(summary = "Estado do conector", description = "enabled/configured/itemCount do usuário autenticado — o app decide se mostra a opção. "
            + "O campo owner é legado (itens são por usuário desde o EC-106) e responde sempre true.")
    @GetMapping("/status")
    public Mono<Map<String, Object>> status(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            if (!provider.enabled()) {
                // "owner" também aqui: o APK publicado lê os QUATRO campos, e
                // com a flag desligada ele sumia da resposta. Vale true pelo
                // mesmo motivo do caminho ligado — desde o EC-106 toda conta é
                // dona das próprias conexões; quem decide se a opção aparece é
                // "enabled"/"configured".
                return Map.<String, Object>of(
                        "enabled", false, "owner", true, "configured", false, "itemCount", 0);
            }
            OpenFinanceProvider.ProviderStatus status = provider.status(email);
            return Map.<String, Object>of(
                    "enabled", true,
                    "owner", true,
                    "configured", status.configured(),
                    "itemCount", status.itemCount());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Connect token para o widget Pluggy Connect",
            description = "Token de curta duração que o app usa para abrir o widget e o usuário conectar uma "
                    + "instituição clicando. Com ?itemId= (de um item já vinculado a esta conta), o widget abre "
                    + "em modo atualização da conexão (credencial expirada/MFA). O token não é a apiKey da "
                    + "aplicação e não dá acesso a dados de outros usuários.")
    @PostMapping("/connect-token")
    public Mono<ConnectTokenResponse> connectToken(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String itemId) {
        return Mono.fromCallable(() -> new ConnectTokenResponse(requireEnabled().connectToken(email, itemId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Registrar item criado pelo widget",
            description = "O app devolve o itemId que o Pluggy Connect entregou no onSuccess. A API confirma no "
                    + "Pluggy que o item existe e pertence a esta conta antes de gravar; item inexistente ou de "
                    + "outra sessão responde 404, itemId já registrado responde 409.")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConnectionResponse> registerItem(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody RegisterPluggyItemRequest request) {
        return Mono.fromCallable(() -> requireEnabled().registerItem(email, request.itemId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Listar conexões do usuário",
            description = "Itens vinculados à conta autenticada, com instituição e carimbo da última "
                    + "sincronização. Nenhum segredo é exposto.")
    @GetMapping("/items")
    public Mono<List<ConnectionResponse>> listItems(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> requireEnabled().listItems(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desvincular conexão",
            description = "Remove o vínculo local e apaga o item no Pluggy (best-effort — revoga o consentimento "
                    + "no agregador). Item de outro usuário responde 404.")
    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unlinkItem(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromRunnable(() -> requireEnabled().unlinkItem(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Sincronizar contas conectadas",
            description = "Percorre os itens DO USUÁRIO autenticado (contas BANK e cartão de crédito) e puxa as "
                    + "transações da janela (default 90 dias) para o mesmo pipeline do upload: categorização, "
                    + "dedup por id e reconciliação entre fontes.")
    @PostMapping("/sync")
    public Mono<Map<String, Object>> sync(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "90") int days) {
        return Mono.fromCallable(() -> {
            OpenFinanceProvider.SyncResult sync = requireEnabled().sync(email, days);
            var result = sync.result();
            // contrato do APK publicado: os campos existentes ficam; só se soma
            Map<String, Object> body = new HashMap<>();
            body.put("uploadId", result.uploadId());
            body.put("transactionsImported", result.transactionsImported());
            body.put("suggested", result.suggested());
            body.put("uncategorized", result.uncategorized());
            body.put("reconciled", result.reconciled());
            body.put("format", result.format());
            body.put("itemsSynced", sync.itemsSynced());
            return body;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sem provedor, o legado responde 400 com a orientação de sempre — o APK
     * publicado mostra essa mensagem; a rota neutra responde 503.
     */
    private OpenFinanceProvider requireEnabled() {
        if (!provider.enabled()) {
            throw new IllegalArgumentException(DISABLED_MESSAGE);
        }
        return provider;
    }
}
