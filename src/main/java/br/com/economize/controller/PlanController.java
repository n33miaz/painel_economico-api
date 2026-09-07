package br.com.economize.controller;

import br.com.economize.dto.plan.PlanInterestRequest;
import br.com.economize.dto.plan.PlansResponse;
import br.com.economize.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "Planos", description = "Gratuito com anúncios ou Plus sem anúncios — oferta, plano vigente e registro de interesse (ainda sem cobrança)")
public class PlanController {

    private final PlanService planService;

    @Operation(summary = "Planos disponíveis e o vigente",
            description = "current é o plano VIGENTE do usuário (Plus vencido aparece como FREE). "
                    + "checkoutAvailable=false enquanto não houver gateway: o app oferece \"tenho interesse\" "
                    + "no lugar de \"assinar\"; interestRegistered evita perguntar de novo.")
    @GetMapping
    public Mono<PlansResponse> plans(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> planService.describe(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Registrar interesse em um plano",
            description = "Idempotente: repetir responde 204 sem criar outro registro. É a medida de demanda "
                    + "que decide se o pagamento será construído.")
    @PostMapping("/interest")
    public Mono<ResponseEntity<Void>> interest(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody PlanInterestRequest request) {
        return Mono.fromCallable(() -> {
            planService.registerInterest(email, request.plan());
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
