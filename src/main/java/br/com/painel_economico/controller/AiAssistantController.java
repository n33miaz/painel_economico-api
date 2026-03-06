package br.com.painel_economico.controller;

import br.com.painel_economico.service.AiAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "Assistente IA", description = "Chatbot financeiro integrado com Google Gemini via Spring AI")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @PostMapping
    @Operation(summary = "Enviar mensagem para a IA", description = "Envia uma pergunta e recebe uma resposta baseada nos dados financeiros do usuário.")
    public Mono<ResponseEntity<Map<String, String>>> askAssistant(
            @AuthenticationPrincipal String email,
            @RequestBody ChatRequest request) {

        return aiAssistantService.askAssistant(email, request.getMessage())
                .map(response -> ResponseEntity.ok(Map.of("reply", response)));
    }
}

@Data
class ChatRequest {
    private String message;
}