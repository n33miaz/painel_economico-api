package br.com.economize.controller;

import br.com.economize.dto.app.AppVersionResponse;
import br.com.economize.service.AppVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
@Tag(name = "Aplicativo", description = "Versão mínima e atual do app, identidade da API e do banco no ar")
public class AppVersionController {

    private final AppVersionService appVersionService;

    @Operation(summary = "Versões do app e da API",
            description = "PÚBLICO — o app consulta antes do login para saber se precisa atualizar. "
                    + "minVersion: abaixo dela toda chamada com X-App-Version responde 426 e o app deve "
                    + "levar ao downloadUrl. apiVersion vem do build-info do jar; schemaVersion é a maior "
                    + "migration do classpath, a mesma que o Flyway aplica.")
    @SecurityRequirements
    @GetMapping("/version")
    public ResponseEntity<AppVersionResponse> version() {
        return ResponseEntity.ok()
                // cinco minutos de cache público: a resposta é igual para todo
                // mundo e muda só em deploy; o app antigo consultando em laço
                // não pode virar carga na instância free do Render
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(appVersionService.describe());
    }
}
