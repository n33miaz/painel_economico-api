package br.com.economize.controller;

import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.statement.parser.StatementFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-statements")
@RequiredArgsConstructor
@Tag(name = "Extratos Bancários", description = "Importação multi-formato (OFX, CSV, XLSX, PDF, TXT) com idempotência por hash")
public class BankStatementController {

    private final BankStatementService bankStatementService;

    @Operation(summary = "Upload de extrato bancário",
            description = "Aceita OFX, CSV, XLSX, PDF e TXT. Idempotente por hash SHA-256. "
                    + "Com `accountId` (de GET /accounts), TODAS as linhas do arquivo passam a saber de qual "
                    + "conta vieram — é o que permite ao Extrato separar duas contas correntes e um cartão em "
                    + "vez de amontoar tudo. Sem ele, o comportamento é o de sempre: origem não informada. "
                    + "Conta de outro usuário responde 404 sem importar nada.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> upload(
            @AuthenticationPrincipal String email,
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestParam(required = false) UUID accountId) {

        return filePartMono
                .flatMap(filePart -> {
                    try {
                        StatementFormat.fromFilename(filePart.filename());
                    } catch (IllegalArgumentException ex) {
                        return Mono.error(ex);
                    }
                    return bankStatementService.processFile(email, filePart, accountId);
                })
                .map(result -> ResponseEntity.ok(Map.of(
                        "message", result.duplicated()
                                ? "Arquivo já importado anteriormente."
                                : "Arquivo processado com sucesso.",
                        "uploadId", result.uploadId(),
                        "transactionsImported", result.transactionsImported(),
                        "suggested", result.suggested(),
                        "uncategorized", result.uncategorized(),
                        "reconciled", result.reconciled(),
                        "format", result.format(),
                        "duplicated", result.duplicated())));
    }

    @Operation(summary = "Listar transações bancárias",
            description = "Histórico inteiro do usuário, do mais recente para o mais antigo. É a "
                    + "fonte da aba Extrato; para recorte por janela/status/categoria use /transactions.")
    @GetMapping
    public Mono<List<BankTransactionResponse>> list(@AuthenticationPrincipal String email) {
        // A resposta é montada INTEIRA dentro da thread bloqueante e devolvida
        // como uma lista: a versão anterior devolvia um Flux e o WebFlux
        // serializava e descarregava elemento a elemento — medido em campo,
        // 1.688 linhas levavam 18 s contra 1,3 s de /transactions com o MESMO
        // payload. No Render, com o timeout de 30 s do app, isso virava
        // "Sem conexão com o servidor" e Extrato vazio para quem tem dois anos
        // de histórico.
        return Mono.fromCallable(() -> bankStatementService.listTransactions(email).stream()
                        .map(BankTransactionResponse::from)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
}
