package br.com.economize.controller;

import br.com.economize.dto.ai.ChatRequest;
import br.com.economize.service.AiAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Assistente IA", description = "Chatbot financeiro integrado com Google Gemini via Spring AI")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    @PostMapping
    @Operation(summary = "Enviar mensagem para a IA",
            description = "Envia uma pergunta e recebe uma resposta baseada nos dados financeiros do usuário. "
                    + "O campo `history` (opcional, até 12 falas) leva a conversa até aqui — sem ele o "
                    + "assistente responde cada pergunta como se fosse a primeira. Os números do contexto "
                    + "vêm sempre do banco, nunca do que o cliente mandou.")
    public Mono<ResponseEntity<Map<String, String>>> askAssistant(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ChatRequest request) {

        return aiAssistantService.askAssistant(email, request.message(), request.history())
                .map(response -> ResponseEntity.ok(Map.of("reply", response)));
    }
}
