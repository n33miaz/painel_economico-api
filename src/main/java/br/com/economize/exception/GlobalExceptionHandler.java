package br.com.economize.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

        @ExceptionHandler(IllegalArgumentException.class)
        public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex) {
                log.warn("Erro de validação: {}", ex.getMessage());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
                problemDetail.setTitle("Requisição Inválida");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/requisicao-invalida")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        /**
         * 404 é resposta normal, não incidente — por isso o log é DEBUG, e não
         * WARN. A Home pergunta pela casa de TODO usuário, e quem não tem casa
         * recebe "Você ainda não faz parte de uma casa" a cada abertura do app:
         * em WARN isso virava a linha mais frequente do log de produção,
         * enterrando os avisos que de fato pedem atenção. O que é anômalo de
         * verdade (enumeração de ids, cliente insistindo num recurso apagado)
         * se enxerga pela métrica de status, não por uma linha por requisição.
         */
        @ExceptionHandler(ResourceNotFoundException.class)
        public ProblemDetail handleResourceNotFoundException(ResourceNotFoundException ex) {
                log.debug("Recurso não encontrado: {}", ex.getMessage());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
                problemDetail.setTitle("Não Encontrado");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/nao-encontrado")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        @ExceptionHandler(ResourceConflictException.class)
        public ProblemDetail handleResourceConflictException(ResourceConflictException ex) {
                log.warn("Conflito de recurso: {}", ex.getMessage());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
                problemDetail.setTitle("Conflito");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/conflito")));
                problemDetail.setProperty("timestamp", Instant.now());
                // identificação do recurso conflitante (ex.: seriesId), para a UI
                // levar direto à edição/reativação em vez de só exibir o texto
                ex.getProperties().forEach(problemDetail::setProperty);
                return problemDetail;
        }

        // Falha de @Valid no WebFlux — sem este handler cairia no genérico como 500
        @ExceptionHandler(WebExchangeBindException.class)
        public ProblemDetail handleValidationException(WebExchangeBindException ex) {
                String detail = ex.getFieldErrors().stream()
                                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                                .collect(Collectors.joining("; "));
                log.warn("Erro de validação de payload: {}", detail);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                                detail.isBlank() ? "Payload inválido" : detail);
                problemDetail.setTitle("Requisição Inválida");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/requisicao-invalida")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        // Binding malformado (UUID inválido na rota, JSON quebrado, query de tipo
        // errado) é erro do cliente: sem este handler cairia no genérico como 500.
        // O WebExchangeBindException acima é subclasse e continua ganhando por
        // especificidade, então falha de @Valid segue com a mensagem por campo.
        @ExceptionHandler(ServerWebInputException.class)
        public ProblemDetail handleServerWebInputException(ServerWebInputException ex) {
                log.warn("Entrada malformada: {}", ex.getReason());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                                "Parâmetro ou corpo da requisição malformado.");
                problemDetail.setTitle("Requisição Inválida");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/requisicao-invalida")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        // EC-107: a instalação não tem como atender um pedido válido (falta
        // configuração de ambiente). Sem este handler cairia no genérico como
        // 500 e mandaria procurar bug onde só falta variável.
        @ExceptionHandler(ServiceUnavailableException.class)
        public ProblemDetail handleServiceUnavailableException(ServiceUnavailableException ex) {
                log.warn("Recurso indisponível nesta instalação: {}", ex.getMessage());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                                ex.getMessage());
                problemDetail.setTitle("Serviço Indisponível");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/servico-indisponivel")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        /**
         * EC-107: falha do provedor de IA do USUÁRIO. Precisa vir antes do
         * handler de WebClientResponseException e ser uma exceção própria por um
         * motivo de segurança, não de estilo: aquele handler registra
         * {@code getResponseBodyAsString()} em log, e corpo bruto de provedor é
         * exatamente onde uma chave ecoada apareceria. A conversão acontece
         * dentro do cliente de IA; aqui só chega motivo classificado e texto
         * escrito por nós.
         */
        @ExceptionHandler(br.com.economize.service.ai.AiProviderException.class)
        public ProblemDetail handleAiProviderException(br.com.economize.service.ai.AiProviderException ex) {
                log.warn("Provedor de IA do usuário falhou: motivo={}", ex.getReason());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                                ex.getMessage());
                problemDetail.setTitle("Erro no Provedor de IA");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/provedor-ia")));
                problemDetail.setProperty("timestamp", Instant.now());
                // motivo classificado para o app decidir o que dizer sem depender
                // do texto; nunca o corpo do provedor
                problemDetail.setProperty("reason", ex.getReason().name());
                return problemDetail;
        }

        @ExceptionHandler(WebClientResponseException.class)
        public ProblemDetail handleWebClientResponseException(WebClientResponseException ex) {
                log.error("Erro na comunicação com API externa: Status={}, Body={}", ex.getStatusCode(),
                                ex.getResponseBodyAsString());
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                                "Falha ao obter dados de provedores externos.");
                problemDetail.setTitle("Erro no Provedor de Dados");
                problemDetail.setType(Objects
                                .requireNonNull(URI.create("https://economize.app/erros/provedor-externo")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        /**
         * Rota que não existe e recurso estático que não foi encontrado. Sem
         * este handler os dois caem no genérico abaixo e viram <b>500</b>: o
         * cliente deixa de conseguir distinguir "errei a URL" de "o servidor
         * quebrou", e todo 404 entra no log como erro. Foi essa conversão que
         * manteve o {@code /swagger-ui} respondendo 500 em produção — a página
         * pede recursos estáticos, cada ausência virava falha interna.
         *
         * <p>Preserva o status que a exceção já carrega em vez de inventar um.
         * {@link ServerWebInputException} e {@link WebExchangeBindException} são
         * subclasses e continuam ganhando por especificidade nos handlers acima,
         * então corpo malformado segue como 400 com a mensagem por campo.
         *
         * <p>O detalhe do 404 é fixo de propósito: {@code getReason()} carrega o
         * caminho pedido, e devolvê-lo seria refletir entrada do cliente na
         * resposta.
         */
        @ExceptionHandler(ResponseStatusException.class)
        public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
                HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
                if (status == null) {
                        status = HttpStatus.INTERNAL_SERVER_ERROR;
                }

                if (status.is5xxServerError()) {
                        // 5xx é problema nosso: precisa da pilha no log
                        log.error("Falha interna com status {}: ", status.value(), ex);
                } else {
                        // 4xx é o cliente pedindo o que não existe — não polui o log de erro
                        log.warn("Requisição respondida com {}: {}", status.value(), ex.getReason());
                }

                boolean notFound = status == HttpStatus.NOT_FOUND;
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status,
                                notFound ? "Recurso não encontrado." : status.getReasonPhrase());
                problemDetail.setTitle(notFound ? "Não Encontrado" : status.getReasonPhrase());
                problemDetail.setType(Objects.requireNonNull(URI.create(
                                notFound ? "https://economize.app/erros/nao-encontrado"
                                                : "https://economize.app/erros/erro-interno")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }

        @ExceptionHandler(Exception.class)
        public ProblemDetail handleGenericException(Exception ex) {
                log.error("Erro inesperado no servidor: ", ex);
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Ocorreu um erro inesperado. Tente novamente mais tarde.");
                problemDetail.setTitle("Erro Interno do Servidor");
                problemDetail.setType(
                                Objects.requireNonNull(URI.create("https://economize.app/erros/erro-interno")));
                problemDetail.setProperty("timestamp", Instant.now());
                return problemDetail;
        }
}