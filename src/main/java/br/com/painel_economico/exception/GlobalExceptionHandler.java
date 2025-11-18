package br.com.painel_economico.exception;

import br.com.painel_economico.dto.ErrorResponseDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponseDTO> handleWebClientResponseException(WebClientResponseException ex) {
        ErrorResponseDTO errorResponse = new ErrorResponseDTO(
                ex.getStatusCode().value(),
                "Erro ao se comunicar com o serviço externo: " + ex.getResponseBodyAsString(),
                Instant.now());
        return new ResponseEntity<>(errorResponse, ex.getStatusCode());
    }

    // adicionar outros @ExceptionHandlers aqui para diferentes erros
}