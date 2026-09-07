package br.com.economize.controller;

import br.com.economize.dto.user.ChangePasswordRequest;
import br.com.economize.dto.user.UpdateUserRequest;
import br.com.economize.dto.user.UserMeResponse;
import br.com.economize.dto.user.UserStatsResponse;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Usuário", description = "Dados do usuário autenticado")
public class UserController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    // Só para contar: o hub do Perfil mostra três números, e antes disso a tela
    // baixava as três listas inteiras para chegar a eles
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final ReportRepository reportRepository;

    @Operation(summary = "Dados do usuário autenticado")
    @GetMapping("/me")
    public Mono<UserMeResponse> me(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> UserMeResponse.from(requireUser(email)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Contadores do hub do Perfil",
            description = "Só os números de extrato, carteira e relatórios. O Perfil desenhava os três "
                    + "contadores baixando as três listas inteiras (100 KB só de extrato para 1.752 linhas) "
                    + "a cada abertura, quando o que aparecia na tela era a contagem.")
    @GetMapping("/me/stats")
    public Mono<UserStatsResponse> stats(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> {
            UUID userId = requireUser(email).getId();
            return new UserStatsResponse(
                    bankTransactionRepository.countByUserId(userId),
                    transactionRepository.countByUserId(userId),
                    reportRepository.countByUserId(userId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Atualizar nome do usuário")
    @PatchMapping("/me")
    public Mono<UserMeResponse> update(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody UpdateUserRequest request) {
        return Mono.fromCallable(() -> {
            User user = requireUser(email);
            user.setName(request.name().trim());
            return UserMeResponse.from(userRepository.save(user));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(operationId = "changePassword", summary = "Alterar senha do usuário autenticado",
            description = "Exige a senha atual; se não conferir, retorna 400.")
    @PostMapping("/me/change-password")
    public Mono<ResponseEntity<Void>> changePassword(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ChangePasswordRequest request) {
        return Mono.fromCallable(() -> {
            passwordService.changePassword(email, request.currentPassword(), request.newPassword());
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
