package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memória do radar de notícias. Guarda, por feed, a última lista BOA de artigos
 * (com os validadores HTTP para o próximo GET condicional) e o agregado pronto
 * para servir: ordenado por data, sem repetição de link, sem artigo velho.
 *
 * <p>É só memória de processo, de propósito: em desligamento nada precisa
 * persistir, porque o primeiro ciclo do agendador reconstrói tudo em segundos.
 * O que importa é que a requisição do usuário nunca espere por rede — ela lê o
 * agregado pronto e só.
 */
@Component
public class NewsSnapshotStore {

    /** Última resposta boa de um feed e o que é preciso para revalidá-la barato. */
    public record FeedSnapshot(List<NewsArticle> articles, Instant fetchedAt, String etag, String lastModified) {
        static final FeedSnapshot EMPTY = new FeedSnapshot(List.of(), null, null, null);
    }

    // publishedAt sem data (ou inválida) vai para o fim da lista, nunca para NPE
    private static final Comparator<NewsArticle> BY_PUBLISHED_DESC = Comparator
            .comparing(NewsSnapshotStore::publishedInstant, Comparator.reverseOrder());

    private final Map<String, FeedSnapshot> feeds = new ConcurrentHashMap<>();
    private final Duration maxAge;
    private final Clock clock;

    // trocados por inteiro a cada rebuild: leitor vê ou o agregado antigo ou o
    // novo, nunca um meio-termo — e a lista é imutável
    private volatile List<NewsArticle> aggregate = List.of();
    private volatile Instant updatedAt;

    @Autowired
    public NewsSnapshotStore(NewsFeedsProperties properties) {
        this(properties.getMaxAge(), Clock.systemUTC());
    }

    /** Relógio injetável: os testes precisam envelhecer artigos sem esperar três dias. */
    public NewsSnapshotStore(Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** Snapshot do feed, ou um vazio (sem validadores) se ele nunca respondeu. */
    public FeedSnapshot feed(String feedId) {
        return feeds.getOrDefault(feedId, FeedSnapshot.EMPTY);
    }

    /** Substitui a lista do feed pela nova resposta boa. */
    public void update(String feedId, List<NewsArticle> articles, String etag, String lastModified) {
        feeds.put(feedId, new FeedSnapshot(List.copyOf(articles), clock.instant(), etag, lastModified));
    }

    /**
     * A fonte respondeu 304: a lista anterior continua valendo e só o instante
     * da confirmação avança. Sem lista anterior não há o que confirmar.
     */
    public void markUnchanged(String feedId) {
        feeds.computeIfPresent(feedId, (id, previous) -> new FeedSnapshot(
                previous.articles(), clock.instant(), previous.etag(), previous.lastModified()));
    }

    /**
     * Recalcula o agregado a partir das listas por feed. Chamado uma vez por
     * ciclo do agendador, depois de todos os feeds — o trabalho de ordenar e
     * deduplicar acontece aqui, uma vez, e não a cada requisição.
     */
    public void rebuild() {
        Instant cutoff = clock.instant().minus(maxAge);
        Set<String> seen = new HashSet<>();
        List<NewsArticle> merged = new ArrayList<>();
        feeds.values().stream()
                .flatMap(snapshot -> snapshot.articles().stream())
                .filter(article -> isRecentEnough(article, cutoff))
                .sorted(BY_PUBLISHED_DESC)
                // fontes republicam umas às outras; o link identifica o artigo, e
                // como a lista já está ordenada fica a versão mais recente
                .filter(article -> seen.add(dedupeKey(article)))
                .forEach(merged::add);
        aggregate = List.copyOf(merged);
        updatedAt = feeds.values().stream()
                .map(FeedSnapshot::fetchedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Agregado pronto para servir: ordenado por data decrescente e imutável. */
    public List<NewsArticle> aggregate() {
        return aggregate;
    }

    /**
     * Instante da última resposta boa de qualquer feed, ou null se nenhum
     * respondeu ainda (boot frio). Se todos os feeds falharem num ciclo, o
     * valor não avança — é assim que o app sabe que está vendo dado velho.
     */
    public Instant updatedAt() {
        return updatedAt;
    }

    private static boolean isRecentEnough(NewsArticle article, Instant cutoff) {
        Instant published = publishedInstant(article);
        // sem data não dá para julgar a idade: fica enquanto a fonte listar
        return published.equals(Instant.EPOCH) || !published.isBefore(cutoff);
    }

    private static String dedupeKey(NewsArticle article) {
        if (article.getUrl() != null && !article.getUrl().isBlank()) {
            return article.getUrl();
        }
        String sourceName = article.getSource() != null ? article.getSource().getName() : "";
        return sourceName + "|" + article.getTitle();
    }

    private static Instant publishedInstant(NewsArticle article) {
        try {
            return OffsetDateTime.parse(article.getPublishedAt()).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
