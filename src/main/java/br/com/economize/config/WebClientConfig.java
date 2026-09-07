package br.com.economize.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Todos os {@code HttpClient} daqui usam o resolvedor de DNS da JVM
 * ({@link DefaultAddressResolverGroup}) em vez do resolvedor assíncrono próprio
 * do Netty. O do Netty lê o resolv.conf e conversa direto com o servidor DNS, e
 * em alguns containers (o do Render entre eles) isso falha ou trava onde a JVM,
 * que usa o resolvedor do sistema, resolve normalmente.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @Primary
    @SuppressWarnings("null")
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * WebClient dedicado a feeds RSS. Feeds grandes (G1 ~620 KB, Valor Investe
     * ~500 KB, InvestNews ~320 KB) estouram o limite padrão de 256 KB do codec
     * (DataBufferLimitException); o limite maior fica isolado aqui para não
     * afrouxar o cliente usado pelas demais integrações.
     *
     * <p>Pede compressão ({@code compress(true)}): esses mesmos feeds saem com
     * gzip em ~100 KB, e na CPU de 0,1 do plano free é a diferença entre baixar
     * o catálogo inteiro no ciclo e estourar timeout em todos ao mesmo tempo.
     */
    @Bean
    @SuppressWarnings("null")
    public WebClient rssWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .followRedirect(true)
                .responseTimeout(Duration.ofSeconds(10))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                        .build())
                .defaultHeader(HttpHeaders.USER_AGENT, "EconomizeApp/2.0 RSS Aggregator")
                .defaultHeader(HttpHeaders.ACCEPT, "application/rss+xml, application/xml, text/xml, */*")
                .build();
    }

    /**
     * WebClient dedicado ao Pluggy (EC-106). Dois motivos para não usar o
     * cliente padrão:
     *
     * <p><b>Tamanho.</b> A v2 de /transactions fixa a página em 500 e não aceita
     * pageSize. Uma transação de cartão vem gorda (paymentData, merchant,
     * creditCardMetadata), então a página passa dos 256 KB do codec padrão e a
     * sincronização inteira morre com DataBufferLimitException — o mesmo
     * acidente que obrigou o rssWebClient acima a existir. 8 MB cobre a página
     * cheia com folga larga.
     *
     * <p><b>Tempo.</b> Os 5 s do cliente padrão são para cotação de moeda; um
     * agregador bancário montando uma página de 500 lançamentos leva mais que
     * isso e o timeout cortaria a sync no meio.
     */
    @Bean
    @SuppressWarnings("null")
    public WebClient pluggyWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * WebClient dedicado às chamadas de IA com a chave do usuário (EC-107). Os
     * 5 s do cliente padrão foram calibrados para cotação de moeda e cortariam
     * quase toda resposta de LLM antes de ela existir — um modelo de raciocínio
     * leva dezenas de segundos para a primeira e única resposta que pedimos. O
     * teto real de espera vem de {@code economize.ai.timeout}, aplicado no
     * {@code block()} de quem chama; aqui a folga é para o transporte não cortar
     * antes.
     *
     * <p>Sem {@code defaultHeader} de Authorization, e isso é proposital: a
     * credencial é de UM usuário e muda a cada chamada. Um default de bean
     * compartilhado seria a chave de alguém vazando para a requisição de outro.
     */
    @Bean
    @SuppressWarnings("null")
    public WebClient aiWebClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .responseTimeout(Duration.ofSeconds(90))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(90, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(90, TimeUnit.SECONDS)));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}