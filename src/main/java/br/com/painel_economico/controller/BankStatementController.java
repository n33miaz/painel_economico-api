package br.com.painel_economico.controller;

import br.com.painel_economico.model.BankTransaction;
import br.com.painel_economico.service.BankStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/bank-statements")
@RequiredArgsConstructor
@Tag(name = "Extratos Bancários", description = "Importação e gestão de extratos via OFX")
public class BankStatementController {

    private final BankStatementService bankStatementService;

    @Operation(summary = "Upload de arquivo OFX", description = "Processa um arquivo OFX e salva as transações na conta do usuário.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> uploadOfx(
            @AuthenticationPrincipal String email,
            @RequestPart("file") Mono<FilePart> filePartMono) {

        return filePartMono
                .flatMap(filePart -> {
                    if (!filePart.filename().toLowerCase().endsWith(".ofx")) {
                        return Mono.error(new IllegalArgumentException("Apenas arquivos .ofx são permitidos."));
                    }
                    return bankStatementService.processOfxFile(email, filePart);
                })
                .map(savedCount -> ResponseEntity.ok(Map.of(
                        "message", "Arquivo processado com sucesso.",
                        "transactionsImported", savedCount)));
    }

    @Operation(summary = "Listar transações bancárias", description = "Retorna todas as transações importadas do usuário.")
    @GetMapping
    public Flux<BankTransaction> getTransactions(@AuthenticationPrincipal String email) {
        return bankStatementService.getUserBankTransactions(email);
    }
}