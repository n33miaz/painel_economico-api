package br.com.economize.service.news;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsTopicInfo;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Radar de relevância do agregador de notícias. Decide se um artigo é sobre
 * dinheiro (economia, investimentos, mercado) e o etiqueta com os tópicos de um
 * vocabulário FIXO, que o app usa como filtro.
 *
 * <p>A regra é assimétrica de propósito, porque a confiança na fonte varia:
 * <ul>
 * <li>feed <b>geral</b> (portal que cobre de tudo) só entra se casar pelo menos
 * um tópico financeiro E não casar a lista de exclusão — é daí que vinham o
 * futebol e o entretenimento;</li>
 * <li>feed <b>financeiro</b> (economia, mercados, investimentos, cripto) entra
 * por padrão e só sai se casar a exclusão sem casar tópico nenhum.</li>
 * </ul>
 *
 * <p>É casamento de palavra inteira, sem diferenciar acento nem caixa: "Selic"
 * casa "selic", "inflação" casa "inflacao", e "gol" não casa "Google".
 */
@Component
public class NewsRelevanceFilter {

    /** Categorias de feed já dedicadas a dinheiro: o artigo entra por padrão. */
    private static final Set<String> FINANCIAL_CATEGORIES = Set.of("economia", "mercados", "investimentos", "cripto");

    private record Topic(String id, String label, Pattern pattern) {
    }

    // Ordem = ordem em que o app exibe os filtros; ids são contrato com o app
    private static final List<Topic> TOPICS = List.of(
            topic("selic-cdi", "Selic e CDI",
                    "selic", "cdi", "copom", "taxa básica", "taxa de juros", "juros", "juro", "corte de juros",
                    "alta de juros", "política monetária", "interest rate", "interest rates", "rate cut", "rate hike",
                    "monetary policy"),
            topic("tesouro", "Tesouro Direto",
                    "tesouro direto", "tesouro selic", "tesouro ipca", "tesouro ipca+", "tesouro prefixado",
                    "tesouro renda+", "tesouro educa+", "título público", "títulos públicos", "ntn-b", "ntnb", "ltn",
                    "lft", "tesouro nacional"),
            topic("inflacao", "Inflação",
                    "inflação", "ipca", "ipca-15", "igp-m", "igpm", "inpc", "deflação", "índice de preços",
                    "custo de vida", "cesta básica", "meta de inflação", "preço dos alimentos", "preços dos alimentos",
                    "inflation", "deflation", "consumer prices", "consumer price index"),
            topic("cambio", "Câmbio",
                    "dólar", "câmbio", "taxa de câmbio", "euro", "ptax", "moeda", "moedas", "libra esterlina", "iene",
                    "yuan", "dollar", "exchange rate", "forex", "currency", "currencies", "usd", "brl"),
            topic("bolsa", "Bolsa",
                    "bolsa", "bolsas", "ibovespa", "b3", "pregão", "ações da bolsa", "mercado de ações",
                    "ações ordinárias", "ações preferenciais", "ações brasileiras", "carteira de ações", "ações sobem",
                    "ações caem", "ações disparam", "ações despencam", "ações recuam", "ações avançam", "ações fecham",
                    "ações abrem", "dividendos", "dividendo", "jcp", "juros sobre capital próprio", "ipo", "follow-on",
                    "small caps", "blue chips", "balanço trimestral", "resultado trimestral", "lucro líquido",
                    "prejuízo líquido", "ebitda", "valor de mercado", "oferta pública", "fusões e aquisições", "m&a",
                    "recuperação judicial", "cvm", "day trade", "home broker", "wall street", "nasdaq", "s&p 500",
                    "sp500", "dow jones", "nyse", "stocks", "stock market", "shares", "equities", "earnings",
                    "market cap", "trader", "traders", "petrobras", "petr4", "petr3", "vale3", "itaú", "itub4",
                    "bradesco", "bbdc4", "ambev", "abev3", "weg", "wege3", "magalu", "mglu3", "nubank", "btg",
                    "xp investimentos", "banco do brasil", "bbas3", "eletrobras", "gerdau", "embraer", "suzano", "jbs"),
            topic("etf-exterior", "ETFs e exterior",
                    "etf", "etfs", "investir no exterior", "investimento no exterior", "investimentos no exterior",
                    "bdr", "bdrs", "ações americanas", "ações nos eua", "ivvb11", "bova11", "smal11", "hash11", "reit",
                    "reits", "conta internacional", "conta global", "dolarizar", "dolarização", "exposição internacional",
                    "mercado americano", "bolsa americana", "bolsas americanas"),
            topic("cripto", "Cripto",
                    "bitcoin", "btc", "ethereum", "ether", "eth", "cripto", "criptomoeda", "criptomoedas", "criptoativo",
                    "criptoativos", "crypto", "cryptocurrency", "cryptocurrencies", "blockchain", "stablecoin",
                    "stablecoins", "altcoin", "altcoins", "solana", "xrp", "binance", "coinbase", "defi", "nft", "nfts",
                    "web3", "halving", "drex", "tether", "usdt", "usdc", "memecoin", "memecoins", "tokenização",
                    "tokenization", "token", "tokens"),
            topic("fiis", "Fundos imobiliários",
                    "fii", "fiis", "fundo imobiliário", "fundos imobiliários", "fundo de investimento imobiliário",
                    "fundos de investimento imobiliário", "ifix", "fiagro", "fiagros", "fundo de tijolo",
                    "fundos de tijolo", "fundo de papel", "fundos de papel"),
            topic("renda-fixa", "Renda fixa",
                    "renda fixa", "cdb", "cdbs", "lci", "lca", "lcis", "lcas", "debênture", "debêntures", "cri", "cra",
                    "letra de crédito", "letras de crédito", "letra financeira", "letras financeiras", "poupança",
                    "fundo di", "fundos di", "prefixado", "pós-fixado", "bond", "bonds", "treasuries", "treasury",
                    "yield", "yields", "fixed income"),
            topic("previdencia", "Previdência",
                    "previdência", "previdência privada", "pgbl", "vgbl", "aposentadoria", "aposentado", "aposentados",
                    "aposentadas", "inss", "benefício do inss", "fundo de pensão", "fundos de pensão",
                    "reforma da previdência", "retirement", "pension", "pensions", "401k"),
            topic("macro-br", "Economia Brasil",
                    "pib", "banco central", "bcb", "bc", "arcabouço fiscal", "meta fiscal", "déficit", "superávit",
                    "dívida pública", "contas públicas", "orçamento", "ministério da fazenda", "ministro da fazenda",
                    "haddad", "galípolo", "ibge", "desemprego", "emprego", "empregos", "caged", "pnad",
                    "reforma tributária", "imposto de renda", "imposto", "impostos", "tributação", "tributos", "icms",
                    "iof", "cofins", "receita federal", "arrecadação", "balança comercial", "exportações",
                    "importações", "tarifas", "tarifaço", "atividade econômica", "ibc-br", "economia brasileira",
                    "economia", "crescimento econômico", "recessão", "juros futuros", "risco fiscal", "agronegócio",
                    "combustíveis", "gasolina", "diesel", "petróleo", "energia elétrica", "conta de luz",
                    "salário mínimo", "bolsa família", "precatórios", "gasto público", "gastos públicos",
                    "sistema financeiro", "mercado financeiro", "custo do dinheiro", "setor produtivo", "indústria",
                    "varejo", "comércio", "pmes", "pequenas empresas", "inadimplência", "concessão de crédito"),
            topic("macro-global", "Economia global",
                    "fed", "federal reserve", "fomc", "powell", "banco central europeu", "bce", "ecb", "central bank",
                    "central banks", "bank of japan", "boj", "bank of england", "boe", "zona do euro", "eurozone",
                    "economia global", "economia mundial", "economia americana", "economia dos eua",
                    "economia chinesa", "guerra comercial", "trade war", "tariff", "tariffs", "fmi", "imf",
                    "banco mundial", "world bank", "ocde", "oecd", "davos", "g20", "g7", "recession", "gdp",
                    "payroll", "juros americanos", "juros nos eua", "commodities", "brent", "opep", "opec", "wti",
                    "oil", "crude", "oil prices", "minério de ferro", "preço do ouro", "gold", "global economy",
                    "recessão global"),
            topic("financas-pessoais", "Finanças pessoais",
                    "finanças pessoais", "educação financeira", "orçamento familiar", "orçamento doméstico",
                    "planejamento financeiro", "reserva de emergência", "independência financeira", "endividamento",
                    "endividados", "dívidas", "dívida", "cartão de crédito", "juros do cartão", "rotativo",
                    "cheque especial", "consignado", "financiamento", "empréstimo", "empréstimos", "crédito", "score",
                    "serasa", "spc", "nome sujo", "negativado", "negativados", "desenrola", "pix", "salário",
                    "13º salário", "décimo terceiro", "fgts", "irpf", "malha fina", "restituição",
                    "declaração do imposto de renda", "investimentos", "investir", "investidor", "investidores",
                    "onde investir", "rendimento", "rendimentos", "renda extra", "patrimônio", "ativo financeiro",
                    "ativos financeiros", "open finance",
                    "conta digital", "consórcio", "cashback", "milhas", "personal finance", "savings", "budget"));

    /**
     * Assuntos que um portal geral publica e o radar não quer: esporte,
     * entretenimento, polícia, clima, cultura, games. Em feed financeiro a
     * exclusão só vale quando o artigo também não fala de dinheiro nenhum.
     */
    private static final Pattern EXCLUSION = wordPattern(
            // esporte
            "futebol", "gol", "gols", "goleiro", "artilheiro", "campeonato", "campeonatos", "série a", "série b",
            "série c", "brasileirão", "libertadores", "copa", "copa do brasil", "copa do mundo", "champions league",
            "premier league", "la liga", "flamengo", "corinthians", "palmeiras", "vasco", "fluminense", "botafogo",
            "grêmio", "cruzeiro", "atlético", "atlético-mg", "são paulo fc", "tricolor", "neymar", "vini jr",
            "seleção brasileira", "cbf", "fifa", "uefa", "olimpíadas", "olímpico", "olímpicos", "medalha", "medalhas",
            "atleta", "atletas", "nba", "nfl", "ufc", "fórmula 1", "f1", "vôlei", "tenista",
            // entretenimento e cultura
            "bbb", "big brother", "novela", "novelas", "celebridade", "celebridades", "famoso", "famosos", "famosa",
            "famosas", "horóscopo", "signo", "signos", "receita culinária", "receitas culinárias", "culinária",
            "gastronomia", "chef", "show", "shows", "festival", "cinema", "filme", "filmes", "bilheteria", "netflix",
            "série de tv", "séries de tv", "ator", "atriz", "cantor", "cantora", "música", "álbum", "turnê",
            // polícia, tragédia, trânsito
            "polícia", "policial", "policiais", "crime", "crimes", "criminoso", "criminosos", "assassinato",
            "assassinado", "assassinada", "homicídio", "feminicídio", "estupro", "sequestro", "tiroteio", "preso",
            "presa", "prisão", "acidente", "acidentes", "morte", "mortes", "morre", "morreu", "morto", "mortos",
            "velório", "trânsito", "engarrafamento",
            // tempo
            "previsão do tempo", "chuva", "chuvas", "frente fria", "onda de calor", "tempestade", "ciclone",
            // games
            "jogo", "jogos", "game", "games", "gamer", "videogame", "videogames", "playstation", "xbox", "nintendo");

    /**
     * Expressões que carregam palavra da exclusão mas são de mercado. Saem do
     * texto antes do teste de exclusão — "jogo de mercado" não é jogo, e a Gol
     * do Ibovespa não é gol.
     */
    private static final Pattern EXCLUSION_EXCEPTIONS = wordPattern(
            "jogo de mercado", "jogos de azar", "gol linhas aéreas", "goll4");

    /** Vocabulário como o app o exibe: id (contrato) e rótulo em pt-BR. */
    public List<NewsTopicInfo> vocabulary() {
        return TOPICS.stream().map(t -> new NewsTopicInfo(t.id(), t.label())).toList();
    }

    /**
     * Ids pedidos que existem no vocabulário, ou null quando não sobra nenhum —
     * um filtro só de ids desconhecidos é ignorado, não devolve lista vazia.
     */
    public Set<String> knownTopics(Set<String> requested) {
        if (requested == null) {
            return null;
        }
        Set<String> known = requested.stream()
                .filter(id -> TOPICS.stream().anyMatch(t -> t.id().equals(id)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return known.isEmpty() ? null : known;
    }

    /** Aplica {@link #classify} e devolve só quem passou, na ordem original. */
    public List<NewsArticle> filter(List<NewsArticle> articles) {
        List<NewsArticle> accepted = new ArrayList<>(articles.size());
        for (NewsArticle article : articles) {
            if (classify(article)) {
                accepted.add(article);
            }
        }
        return accepted;
    }

    /**
     * Etiqueta o artigo com os tópicos que casou e diz se ele entra no radar,
     * segundo a regra assimétrica da classe.
     */
    public boolean classify(NewsArticle article) {
        String text = normalize(joinText(article));
        List<String> topics = TOPICS.stream()
                .filter(t -> t.pattern().matcher(text).find())
                .map(Topic::id)
                .toList();
        article.setTopics(topics);

        boolean excluded = EXCLUSION.matcher(EXCLUSION_EXCEPTIONS.matcher(text).replaceAll(" ")).find();
        if (isFinancialSource(article)) {
            return !(excluded && topics.isEmpty());
        }
        return !topics.isEmpty() && !excluded;
    }

    private static boolean isFinancialSource(NewsArticle article) {
        return article.getSourceCategory() != null
                && FINANCIAL_CATEGORIES.contains(article.getSourceCategory().toLowerCase(Locale.ROOT));
    }

    private static String joinText(NewsArticle article) {
        String title = article.getTitle() != null ? article.getTitle() : "";
        String description = article.getDescription() != null ? article.getDescription() : "";
        return title + " " + description;
    }

    /** Minúsculas e sem diacríticos (NFKD também resolve "13º" em "13o"). */
    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static Topic topic(String id, String label, String... keywords) {
        return new Topic(id, label, wordPattern(keywords));
    }

    /**
     * Alternância das palavras-chave normalizadas, exigindo fronteira de palavra
     * dos dois lados. Não usa \b porque "s&p 500" e "ipca-15" têm caracteres
     * que \b trataria como fronteira interna.
     */
    private static Pattern wordPattern(String... keywords) {
        String alternatives = Arrays.stream(keywords)
                .map(NewsRelevanceFilter::normalize)
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])");
    }
}
