package br.com.economize.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * WebClient de verdade com transporte falso: a função recebe a requisição
 * montada (URL final, cabeçalhos) e devolve a resposta. Não há OkHttp
 * MockWebServer nem WireMock no projeto, e dublar a cadeia fluente do WebClient
 * com Mockito esconde justamente o que estes testes querem ver — a URL que
 * saiu e o parse do corpo que voltou.
 */
public final class StubWebClient {

    private StubWebClient() {
    }

    /** Toda URL pedida é registrada em {@code requestedUrls}, na ordem. */
    public static WebClient of(List<String> requestedUrls, Function<ClientRequest, Mono<ClientResponse>> responder) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUrls.add(request.url().toString());
                    return responder.apply(request);
                })
                .build();
    }

    public static WebClient respondingWith(List<String> requestedUrls,
            Function<ClientRequest, ClientResponse> responder) {
        return of(requestedUrls, request -> Mono.just(responder.apply(request)));
    }

    public static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    public static ClientResponse text(String contentType, String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(body)
                .build();
    }

    public static ClientResponse status(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
