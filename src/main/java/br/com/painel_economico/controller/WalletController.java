package br.com.painel_economico.controller;

import br.com.painel_economico.dto.wallet.TransactionRequest;
import br.com.painel_economico.dto.wallet.TransactionResponse;
import br.com.painel_economico.service.WalletService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Gerenciamento da carteira de investimentos do usuário")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/transactions")
    public Flux<TransactionResponse> getTransactions(@AuthenticationPrincipal String email) {
        return walletService.getUserTransactions(email);
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TransactionResponse> addTransaction(
            @AuthenticationPrincipal String email,
            @RequestBody TransactionRequest request) {
        return walletService.addTransaction(email, request);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTransaction(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return walletService.deleteTransaction(email, id);
    }
}