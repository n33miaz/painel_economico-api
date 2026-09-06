package br.com.economize.controller;

import br.com.economize.dto.auth.MfaActivateRequest;
import br.com.economize.dto.auth.MfaDisableRequest;
import br.com.economize.dto.auth.MfaRecoveryCodesResponse;
import br.com.economize.dto.auth.MfaSetupResponse;
import br.com.economize.dto.auth.MfaStatusResponse;
import br.com.economize.dto.auth.TrustedDeviceResponse;
import br.com.economize.service.MfaService;
import br.com.economize.service.TrustedDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Cadastro e gestão do segundo fator, sempre com sessão já aberta.
 *
 * <p>Fica sob {@code /api/v1/mfa} e NÃO sob {@code /api/v1/auth/**}, que é
 * público: ligar, desligar e ver o estado do fator de uma conta exigem estar
 * dentro dela. O único passo público é o segundo do login, que vive no
 * {@link AuthController} e se defende pelo desafio assinado.
 */
@RestController
@RequestMapping("/api/v1/mfa")
@RequiredArgsConstructor
@Tag(name = "Segundo fator", description = "Verificação em duas etapas por TOTP (RFC 6238)")
public class MfaController {

    private final MfaService mfaService;
    private final TrustedDeviceService deviceService;

    @Operation(summary = "Estado do segundo fator desta conta",
            description = "Nunca devolve o segredo. `pendingConfirmation` marca cadastro começado e não "
                    + "confirmado — nesse estado o login ainda não exige código.")
    @GetMapping
    public Mono<MfaStatusResponse> status(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> mfaService.status(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Começar o cadastro",
            description = "Gera um segredo novo e devolve o QR (otpauthUri) e o segredo em texto. É a ÚNICA "
                    + "resposta da API que carrega o segredo. Repetir a chamada antes de confirmar troca o "
                    + "segredo; com o fator já ativo responde 400.")
    @PostMapping("/setup")
    public Mono<MfaSetupResponse> setup(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> mfaService.startSetup(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Confirmar e ativar",
            description = "Confere o primeiro código do autenticador. Só depois disso o login passa a exigir "
                    + "o segundo passo. Devolve os códigos de recuperação — a única vez que eles aparecem.")
    @PostMapping("/activate")
    public Mono<MfaRecoveryCodesResponse> activate(@AuthenticationPrincipal String email,
                                                   @Valid @RequestBody MfaActivateRequest request) {
        return Mono.fromCallable(() -> mfaService.activate(email, request.code()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Gerar novos códigos de recuperação",
            description = "Descarta o lote anterior por inteiro. Use quando os códigos acabarem ou vazarem.")
    @PostMapping("/recovery-codes")
    public Mono<MfaRecoveryCodesResponse> rotateRecoveryCodes(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> mfaService.rotateRecoveryCodes(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Aparelhos que dispensam o segundo fator",
            description = "A lista dos que já provaram quem são. Nunca devolve o segredo — só o rótulo e as "
                    + "datas, para a pessoa reconhecer o que está ali e esquecer o que não reconhece.")
    @GetMapping("/devices")
    public Mono<java.util.List<TrustedDeviceResponse>> devices(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> deviceService.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Esquecer um aparelho",
            description = "O próximo login dele volta a pedir código. Aparelho de outro usuário responde 404.")
    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> forgetDevice(@AuthenticationPrincipal String email,
                                   @PathVariable java.util.UUID deviceId) {
        return Mono.<Void>fromRunnable(() -> deviceService.forget(email, deviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Esquecer TODOS os aparelhos",
            description = "O botão de \"perdi o celular\": todo login volta a pedir código.")
    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> forgetAllDevices(@AuthenticationPrincipal String email) {
        return Mono.<Void>fromRunnable(() -> deviceService.forgetAll(email))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Desligar o segundo fator",
            description = "Exige a SENHA da conta, e não um código: quem está com o celular desbloqueado na "
                    + "mão tem o autenticador ali — a senha é o que essa pessoa não tem.")
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> disable(@AuthenticationPrincipal String email,
                              @Valid @RequestBody MfaDisableRequest request) {
        return Mono.<Void>fromRunnable(() -> mfaService.disable(email, request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
