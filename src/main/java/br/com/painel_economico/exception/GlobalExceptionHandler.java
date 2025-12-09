package br.com.painel_economico.exception;

import br.com.painel_economico.dto.ErrorResponse;
import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleWebClientResponseException(WebClientResponseException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                ex.getStatusCode().value(),
                "Erro ao se comunicar com o serviço externo: " + ex.getResponseBodyAsString(),
                Instant.now());
        return new ResponseEntity<>(errorResponse, ex.getStatusCode());
    }

    // adicionar outros @ExceptionHandlers aqui para diferentes erros
}