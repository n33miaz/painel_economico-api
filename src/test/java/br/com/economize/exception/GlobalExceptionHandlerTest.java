package br.com.economize.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava o handler de {@link ResponseStatusException}.
 *
 * <p>Antes dele, rota inexistente e recurso estático ausente caíam no
 * {@code @ExceptionHandler(Exception.class)} e voltavam como <b>500</b>. O
 * sintoma visível era o {@code /swagger-ui} respondendo erro interno em
 * produção; o invisível era todo 404 entrando no log como falha do servidor.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("rota inexistente devolve 404, e não 500")
    void rotaInexistenteDevolve404() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "404 NOT_FOUND");

        ProblemDetail problem = handler.handleResponseStatusException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Não Encontrado");
        assertThat(problem.getType()).hasToString("https://economize.app/erros/nao-encontrado");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    @Test
    @DisplayName("o detalhe do 404 não reflete o caminho pedido pelo cliente")
    void naoRefleteCaminhoDoCliente() {
        // getReason() do WebFlux carrega o caminho; devolvê-lo seria ecoar
        // entrada do cliente dentro da resposta
        ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.NOT_FOUND, "/api/v1/auth/<script>alert(1)</script>");

        ProblemDetail problem = handler.handleResponseStatusException(ex);

        assertThat(problem.getDetail())
                .isEqualTo("Recurso não encontrado.")
                .doesNotContain("script");
    }

    @Test
    @DisplayName("preserva o status que a exceção carrega, em vez de inventar um")
    void preservaStatusOriginal() {
        ProblemDetail metodo = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
        ProblemDetail tipo = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        assertThat(metodo.getStatus()).isEqualTo(405);
        assertThat(tipo.getStatus()).isEqualTo(415);
    }

    @Test
    @DisplayName("recurso não encontrado devolve 404 sem uma linha de WARN no log")
    void recursoNaoEncontradoNaoGeraWarn() {
        // A Home pergunta pela casa de TODO usuário: "Você ainda não faz parte
        // de uma casa" era a linha mais frequente do log de produção, em WARN.
        // 404 é resposta normal, e o log dele é DEBUG.
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProblemDetail problem = handler.handleResourceNotFoundException(
                    new ResourceNotFoundException("Você ainda não faz parte de uma casa"));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getDetail()).isEqualTo("Você ainda não faz parte de uma casa");
            assertThat(problem.getTitle()).isEqualTo("Não Encontrado");
            assertThat(appender.list)
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("status fora da tabela conhecida não derruba o handler")
    void statusDesconhecidoViraErroInterno() {
        // HttpStatus.resolve devolve null para código não padronizado; sem o
        // fallback o handler estouraria dentro do próprio tratamento de erro
        ResponseStatusException ex = new ResponseStatusException(HttpStatusCode.valueOf(599));

        ProblemDetail problem = handler.handleResponseStatusException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
}
