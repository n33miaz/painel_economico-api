package br.com.painel_economico.exception;

import br.com.painel_economico.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex,
            ServerHttpRequest request) {
        log.warn("Erro de validação: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Requisição Inválida", ex.getMessage(), request);
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebClientResponseException(WebClientResponseException ex,
            ServerHttpRequest request) {
        log.error("Erro na comunicação com API externa: Status={}, Body={}", ex.getStatusCode(),
                ex.getResponseBodyAsString());
        return buildResponse(HttpStatus.BAD_GATEWAY, "Erro no Provedor de Dados", "Falha ao obter dados externos.",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, ServerHttpRequest request) {
        log.error("Erro inesperado no servidor: ", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Erro Interno",
                "Ocorreu um erro inesperado. Tente novamente mais tarde.", request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error, String message,
            ServerHttpRequest request) {
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(error)
                .message(message)
                .path(request.getPath().value())
                .build();
        return new ResponseEntity<>(response, status);
    }
}