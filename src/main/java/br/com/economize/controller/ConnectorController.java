package br.com.economize.controller;

import br.com.economize.dto.connector.ConnectTokenResponse;
import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.ConnectorStatusResponse;
import br.com.economize.dto.connector.ConnectorSyncResponse;
import br.com.economize.dto.connector.RegisterConnectionRequest;
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

import java.util.List;
import java.util.UUID;

/**
 * Conexão bancária (Open Finance) sem nome de provedor — a rota que o app novo
 * usa. O que está por trás é o {@link OpenFinanceProvider} escolhido na
 * instalação; trocar de agregador não muda uma linha daqui nem do app.
 *
 * <p>A rota antiga {@code /api/v1/connectors/pluggy/**} continua existindo
 * para o APK 2.2.0 e delega ao MESMO provedor.
 */
@RestController
@RequestMapping("/api/v1/connectors")
@RequiredArgsConstructor
@Tag(name = "Conexão bancária", description = "Open Finance sem nome de provedor: status, widget, conexões e sincronização do usuário autenticado")
public class ConnectorController {

    private final OpenFinanceProvider provider;

    @Operation(summary = "Estado do conector",
            description = "enabled=false: esta instalação não tem conector e o app esconde a seção. "
                    + "configured: o usuário consegue sincronizar agora. provider traz só o nome neutro; "
                    + "widget diz qual script a página-ponte do site carrega para abrir a conexão.")
    @GetMapping("/status")
    public Mono<ConnectorStatusResponse> status(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            OpenFinanceProvider.ProviderStatus status = provider.status(email);
            return new ConnectorStatusResponse(status.enabled(), status.configured(), status.itemCount(),
                    new ConnectorStatusResponse.ProviderInfo(provider.id(), provider.displayName()),
                    provider.widget());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Token para abrir o widget de conexão",
            description = "Token de curta duração que a página-ponte usa para abrir o widget do provedor. "
                    + "Com ?itemId= (de uma conexão desta conta), abre em modo atualização da conexão "
                    + "(credencial expirada/MFA). Sem conector responde 503.")
    @PostMapping("/connect-token")
    public Mono<ConnectTokenResponse> connectToken(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String itemId) {
        return Mono.fromCallable(() -> new ConnectTokenResponse(provider.connectToken(email, itemId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Registrar a conexão criada pelo widget",
            description = "O app devolve o itemId recebido no onSuccess. A API confirma no provedor que a "
                    + "conexão existe e pertence a esta conta antes de gravar; inexistente ou de outra sessão "
                    + "responde 404, já registrada responde 409.")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConnectionResponse> registerItem(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody RegisterConnectionRequest request) {
        return Mono.fromCallable(() -> provider.registerItem(email, request.itemId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Listar conexões do usuário",
            description = "Conexões vinculadas à conta autenticada, com a instituição e o carimbo da última "
                    + "sincronização. Nenhum segredo é exposto.")
    @GetMapping("/items")
    public Mono<List<ConnectionResponse>> listItems(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> provider.listItems(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desvincular conexão",
            description = "Remove o vínculo local e revoga a conexão no provedor (best-effort). "
                    + "Conexão de outro usuário responde 404.")
    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unlinkItem(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromRunnable(() -> provider.unlinkItem(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Sincronizar contas conectadas",
            description = "Percorre as conexões do usuário e traz as transações da janela (default 90 dias) "
                    + "para o mesmo pipeline do upload: categorização, dedup e reconciliação entre fontes.")
    @PostMapping("/sync")
    public Mono<ConnectorSyncResponse> sync(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "90") int days) {
        return Mono.fromCallable(() -> ConnectorSyncResponse.from(provider.sync(email, days)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
