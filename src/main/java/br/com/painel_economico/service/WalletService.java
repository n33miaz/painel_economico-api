package br.com.painel_economico.service;

import br.com.painel_economico.dto.wallet.TransactionRequest;
import br.com.painel_economico.dto.wallet.TransactionResponse;
import br.com.painel_economico.model.Transaction;
import br.com.painel_economico.model.User;
import br.com.painel_economico.repository.TransactionRepository;
import br.com.painel_economico.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class WalletService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public Flux<TransactionResponse> getUserTransactions(String email) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
            return transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId());
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(list -> list)
                .map(this::mapToResponse);
    }

    public Mono<TransactionResponse> addTransaction(String email, TransactionRequest request) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

            Transaction transaction = Transaction.builder()
                    .user(user)
                    .assetCode(request.getAssetCode())
                    .type(request.getType())
                    .quantity(request.getQuantity())
                    .priceAtTransaction(request.getPriceAtTransaction())
                    .build();

            Transaction saved = transactionRepository.save(transaction);
            log.info("Transação salva com sucesso para o usuário: {}", email);
            return mapToResponse(saved);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteTransaction(String email, UUID transactionId) {
        return Mono.fromRunnable(() -> {
            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transação não encontrada"));

            if (!transaction.getUser().getEmail().equals(email)) {
                throw new SecurityException("Acesso negado a esta transação");
            }

            transactionRepository.delete(transaction);
            log.info("Transação {} deletada pelo usuário {}", transactionId, email);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private TransactionResponse mapToResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .assetCode(t.getAssetCode())
                .type(t.getType())
                .quantity(t.getQuantity())
                .priceAtTransaction(t.getPriceAtTransaction())
                .transactionDate(t.getTransactionDate())
                .build();
    }
}