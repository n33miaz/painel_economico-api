package br.com.economize.service.statement.category;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Vocabulário do sistema. Depois da V9 cada entrada aponta para uma SUBcategoria
 * (a unidade de intenção, como no Plaid PFCv2 e na árvore do Pluggy) e carrega o
 * pai como rede: se o seed da subcategoria não existir, a transação ainda cai na
 * categoria certa em vez de ir para a fila.
 *
 * Keywords calibradas com os extratos reais de 2026-08 (Inter, 2 anos; Nubank,
 * 4 meses): autopass/bilhete único = transporte público SP, sabesp/enel =
 * concessionárias, cdb/tesouro/resgate/aplicação = o vocabulário de investimento
 * que representa ~20% do extrato do Inter.
 */
@Service
public class RuleBasedCategorizationService {

    /**
     * Subcategoria alvo, o pai como fallback, e os termos que a acionam.
     *
     * <p>{@code priority} existe porque comprimento nem sempre mede
     * especificidade: "Transferência enviada pelo Pix" contém a palavra genérica
     * (13 letras) e o método (3), e é o método que diz para onde a transação vai.
     *
     * <p>Os métodos de transferência vivem numa faixa ABAIXO do vocabulário de
     * estabelecimento ({@link #METHOD_PIX} / {@link #METHOD_GENERIC}): quem paga o
     * iFood por Pix comprou comida, não fez "uma transferência". Só quando nada
     * mais casa — Pix para uma pessoa, por exemplo — é que o meio vira a resposta.
     *
     * <p>{@code wholeWord} é para termos curtos demais para casar por pedaço —
     * "pix" solto acha "pixel". O padrão continua sendo pedaço, porque é ele que
     * faz "restaurant" cobrir restaurante(s) e "lanche" cobrir lanchonete.
     */
    private record Rule(String systemKey, String parentKey, int priority,
                        boolean wholeWord, List<String> keywords) {
        Rule(String systemKey, String parentKey, List<String> keywords) {
            this(systemKey, parentKey, 0, false, keywords);
        }
    }

    /** Keyword já resolvida em como testar — compilada uma vez, não por transação. */
    private record Term(String systemKey, String parentKey, int priority,
                        int length, Pattern pattern, String literal) {

        boolean matches(String haystack) {
            return pattern != null ? pattern.matcher(haystack).find() : haystack.contains(literal);
        }
    }

    public record Hit(String systemKey, String parentKey) {
    }

    /**
     * A marca identifica o estabelecimento; a palavra genérica só o descreve.
     * Sem isso, "IFOOD.COM AGENCIA DE RESTAURANTES ONLINE" casa "restaurant" (10)
     * antes de "ifood" (5) e o pedido de delivery vira refeição no restaurante.
     */
    private static final int BRAND = 1;

    /** O meio específico ainda vence o hiperônimo: "Transferência ... pelo Pix" é Pix. */
    private static final int METHOD_PIX = -1;
    private static final int METHOD_GENERIC = -2;

    private static final List<Rule> RULES = List.of(
            // Alimentação
            new Rule("FOOD_DELIVERY", "FOOD", BRAND, false,
                    List.of("ifood", "rappi", "uber eats", "delivery", "james delivery")),
            // "IFD*" é como o iFood aparece na maquininha, e é o que chega no
            // extrato de cartão e de vale-refeição: "IFD*FOOD ACAI LTDA",
            // "IFD*REI DO ARTESANAL H". Medido no extrato real do Flash — sem
            // isto, QUATRO grupos de pedidos ficavam sem sugestão nenhuma,
            // porque a palavra "ifood" só aparece em alguns deles.
            // Palavra inteira de propósito: "ifd" tem três letras e, solto,
            // casaria dentro de qualquer nome de estabelecimento.
            new Rule("FOOD_DELIVERY", "FOOD", BRAND, true,
                    List.of("ifd", "99food", "99 food", "aiqfome", "zedelivery")),
            new Rule("FOOD_GROCERIES", "FOOD", List.of("supermercado", "mercadinho", "minimercado", "mercearia", "adega", "hortifruti", "sacolao", "sacolão", "atacadao", "atacadão", "assai", "assaí", "carrefour", "roldao", "roldão", "pao de acucar", "pão de açúcar", "big bompreco", "extra super")),
            new Rule("FOOD_COFFEE", "FOOD", List.of("padaria", "cafeteria", "starbucks", "confeitaria", "doceria")),
            // "restaurant"/"lanch" como prefixo cobrem restaurante(s), lanche(s) e
            // lanchonete(s). Era "lanche", que NÃO está dentro de "lanchonete"
            // (lanch-o-nete): medido no extrato real (EC-111), 6 compras em
            // lanchonetes ficavam sem categoria e 4 PIX para lanchonete caíam em
            // Transferências
            new Rule("FOOD_RESTAURANT", "FOOD", List.of("restaurant", "lanch", "pizzaria", "churrascaria", "hamburgueria", "bar e ", "burger", "mc donalds", "mcdonald", "burger king", "subway", "outback", "habibs", "habib's")),
            // Transporte
            new Rule("TRANSPORT_RIDE", "TRANSPORT", List.of("uber", "99app", "99 pop", "99pop", "cabify", "indriver")),
            new Rule("TRANSPORT_FUEL", "TRANSPORT", List.of("posto", "gasolina", "ipiranga", "shell", "petrobras", "combustivel", "combustível", "etanol")),
            new Rule("TRANSPORT_PUBLIC", "TRANSPORT", List.of("autopass", "bilhete unico", "bilhete único", "meio de transporte", "metro ", "metrô", "cptm", "onibus", "ônibus", "sptrans", "riocard", "bom cartao", "bom cartão")),
            new Rule("TRANSPORT_PARKING", "TRANSPORT", List.of("estacionamento", "zona azul", "pedagio", "pedágio", "sem parar", "conectcar", "veloe", "parking")),
            new Rule("TRANSPORT_VEHICLE", "TRANSPORT", List.of("ipva", "licenciamento", "detran", "oficina", "auto center", "pneu", "revisao veicular", "revisão veicular", "multa de transito", "multa de trânsito")),
            // Moradia
            new Rule("HOUSING_RENT", "HOUSING", List.of("aluguel", "imobiliaria", "imobiliária", "locacao imovel", "locação imóvel")),
            new Rule("HOUSING_CONDO", "HOUSING", List.of("condominio", "condomínio")),
            new Rule("HOUSING_PROPERTY_TAX", "HOUSING", List.of("iptu")),
            new Rule("HOUSING_GOODS", "HOUSING", List.of("leroy merlin", "telhanorte", "casa e construcao", "casa e construção", "material de construcao", "material de construção", "tok stok", "mobly", "madeiramadeira")),
            // Contas e serviços
            new Rule("UTILITIES_ELECTRICITY", "UTILITIES", List.of("energia", "eletropaulo", "enel", "cemig", "copel", "light servicos", "light serviços", "celesc", "coelba", "neoenergia", "cpfl")),
            new Rule("UTILITIES_WATER", "UTILITIES", List.of("sabesp", "saneamento", "cedae", "copasa", "sanepar", "embasa", "agua e esgoto", "água e esgoto")),
            new Rule("UTILITIES_GAS", "UTILITIES", List.of("comgas", "comgás", "naturgy", "ultragaz", "liquigas", "liquigás")),
            new Rule("UTILITIES_INTERNET", "UTILITIES", List.of("internet", "banda larga", "net servicos", "net serviços", "sky ", "oi fibra", "vivo fibra", "telecomunicacoes", "telecomunicações")),
            // "recarga" é segura mesmo com a do transporte existindo: a keyword mais
            // longa vence, e "autopass"/"bilhete unico" são mais longas
            // operadoras são palavras curtas e comuns ("tim", "vivo", "claro"):
            // só valem como palavra inteira
            new Rule("UTILITIES_PHONE", "UTILITIES", 0, true, List.of("recarga", "telefone", "celular", "inter cel", "vivo ", "claro ", "tim ", "oi movel", "oi móvel")),
            // Saúde
            new Rule("HEALTH_PHARMACY", "HEALTH", List.of("farmacia", "farmácia", "drogaria", "drogasil", "droga raia", "pacheco", "panvel", "pague menos")),
            new Rule("HEALTH_DENTAL", "HEALTH", List.of("odonto", "dentista", "odontologia")),
            // achado com extrato real: "amil" vive dentro de "CAMILA" e mandava um
            // Pix recebido de uma pessoa para Plano de saúde
            new Rule("HEALTH_INSURANCE", "HEALTH", 0, true, List.of("plano de saude", "plano de saúde", "unimed", "amil", "bradesco saude", "bradesco saúde", "sulamerica saude", "sulamérica saúde", "hapvida")),
            new Rule("HEALTH_CARE", "HEALTH", List.of("consulta", "clinica", "clínica", "hospital", "exame", "laboratorio", "laboratório", "fleury", "delboni")),
            // Seguros
            new Rule("INSURANCE_VEHICLE", "INSURANCE", List.of("seguro auto", "seguro do carro", "seguro veicular", "porto seguro auto")),
            new Rule("INSURANCE_HOME", "INSURANCE", List.of("seguro residencial", "seguro casa")),
            // Por PALAVRA INTEIRA, e sem o plural: achado medindo a fatura de
            // cartão no EC-113 — "seguro" como pedaço vive dentro de "PAGSEGURO",
            // e o PagSeguro é uma das maiores maquininhas do país. Toda linha
            // "PAGSEGURO *MERCANTE" da fatura virava Seguro de vida, e o usuário
            // aprovando na revisão ensinava a regra errada.
            //
            // O plural "seguros" foi tentado junto e teve que sair: o desempate é
            // por COMPRIMENTO ESTRITO (ver match), então "seguros" (7) vence
            // "unimed" (6) e mandava "SEGUROS UNIMED" — empresa real de PLANO DE
            // SAÚDE — para Seguro de vida. Sem ele, "seguro" (6) empata com
            // "unimed" e perde por chegar depois, que é o resultado certo. E o
            // plural não faz falta: a palavra inteira "seguro" não casa dentro de
            // "SEGUROS" mesmo, porque a próxima letra é uma letra.
            new Rule("INSURANCE_LIFE", "INSURANCE", 0, true,
                    List.of("seguro", "protecao financeira", "proteção financeira", "prestamista")),
            // Cuidado pessoal
            new Rule("PERSONAL_GYM", "PERSONAL_CARE", List.of("academia", "smart fit", "smartfit", "bluefit", "crossfit", "gympass", "totalpass", "wellhub", "pilates")),
            new Rule("PERSONAL_BEAUTY", "PERSONAL_CARE", List.of("barbearia", "salao de beleza", "salão de beleza", "cabeleireiro", "manicure", "estetica", "estética", "spa ")),
            new Rule("PERSONAL_LAUNDRY", "PERSONAL_CARE", List.of("lavanderia", "lava e seca")),
            // Educação
            new Rule("EDUCATION_COURSES", "EDUCATION", List.of("udemy", "coursera", "alura", "curso ", "hotmart", "rocketseat", "duolingo")),
            new Rule("EDUCATION_SCHOOL", "EDUCATION", List.of("escola", "faculdade", "universidade", "colegio", "colégio", "mensalidade escolar", "creche")),
            new Rule("EDUCATION_BOOKS", "EDUCATION", List.of("livraria", "saraiva", "cultura livraria", "material escolar")),
            // Lazer
            new Rule("LEISURE_STREAMING", "LEISURE", List.of("netflix", "spotify", "disney plus", "hbo max", "prime video", "deezer", "youtube premium", "globoplay", "paramount", "apple music", "apple tv")),
            new Rule("LEISURE_GAMES", "LEISURE", List.of("steam", "playstation", "xbox", "nintendo", "epic games", "riot games", "blizzard")),
            new Rule("LEISURE_EVENTS", "LEISURE", List.of("cinema", "cinemark", "ingresso", "ticketmaster", "sympla", "teatro", "show ", "eventim")),
            new Rule("LEISURE_TRAVEL", "LEISURE", List.of("airbnb", "booking", "hotel", "pousada", "latam", "gol linhas", "azul linhas", "decolar", "cvc viagens", "hostel")),
            // Compras
            new Rule("SHOPPING_ONLINE", "SHOPPING", List.of("mercado livre", "mercadolivre", "amazon", "shopee", "aliexpress", "shein", "magalu", "magazine luiza", "americanas", "temu")),
            new Rule("SHOPPING_ELECTRONICS", "SHOPPING", List.of("kabum", "pichau", "terabyte", "fast shop", "apple store", "samsung")),
            new Rule("SHOPPING_CLOTHING", "SHOPPING", List.of("renner", "riachuelo", "c&a", "zara", "hering", "centauro", "netshoes", "calcados", "calçados", "nike", "adidas")),
            new Rule("SHOPPING_PET", "SHOPPING", List.of("petz", "cobasi", "pet shop", "petshop", "veterinar")),
            new Rule("SHOPPING_GIFTS", "SHOPPING", List.of("presente", "floricultura", "cacau show", "kopenhagen")),
            // Impostos e tarifas
            // por palavra inteira: "iof" solto acha "biofarma"
            new Rule("FEES_IOF", "FEES_TAXES", 0, true, List.of("iof")),
            new Rule("FEES_BANK", "FEES_TAXES", List.of("tarifa", "anuidade", "cesta de servicos", "cesta de serviços", "taxa de manutencao", "taxa de manutenção")),
            // "mora" por palavra inteira: como pedaço, achava "AMORA"
            new Rule("FEES_INTEREST", "FEES_TAXES", 0, true, List.of("juros", "multa por atraso", "encargos", "mora ")),
            new Rule("FEES_TAX", "FEES_TAXES", List.of("imposto", "darf", "irrf", "tributo", "das simples")),
            // Receitas
            new Rule("INCOME_SALARY", "INCOME", List.of("salario", "salário", "folha de pagamento", "adiantamento salarial", "13o salario", "férias")),
            new Rule("INCOME_CASHBACK", "INCOME", List.of("cashback", "estorno", "reembolso", "devolucao", "devolução")),
            new Rule("INCOME_YIELDS", "INCOME", List.of("rendimento", "dividendo", "jcp", "juros sobre capital", "proventos")),
            new Rule("INCOME_BENEFITS", "INCOME", List.of("bolsa familia", "bolsa família", "auxilio", "auxílio", "inss", "seguro desemprego", "pis pasep")),
            new Rule("INCOME_FREELANCE", "INCOME", List.of("freela", "prestacao de servico", "prestação de serviço", "nota fiscal recebida")),
            // Investimentos
            new Rule("INVESTMENT_FIXED", "INVESTMENT", List.of("cdb", "rdb", "lci", "lca", "tesouro", "poupanca", "poupança", "aplicacao", "aplicação", "renda fixa", "cofrinho", "porquinho", "caixinha")),
            new Rule("INVESTMENT_REDEMPTION", "INVESTMENT", List.of("resgate")),
            new Rule("INVESTMENT_CRYPTO", "INVESTMENT", List.of("bitcoin", "binance", "mercado bitcoin", "foxbit", "cripto")),
            // "clear" como pedaço acha "NUCLEAR"
            new Rule("INVESTMENT_VARIABLE", "INVESTMENT", 0, true, List.of("corretora", "clear ", "rico investimentos", "xp investimentos", "b3 ", "conta global de inv")),
            new Rule("INVESTMENT_FUNDS", "INVESTMENT", List.of("fundo de investimento", "fii ")),
            new Rule("INVESTMENT_PENSION", "INVESTMENT", List.of("previdencia", "previdência", "pgbl", "vgbl")),
            // Transferências
            new Rule("TRANSFER_PIX", "TRANSFER", METHOD_PIX, true, List.of("pix")),
            // "ted" solto acha "limited"/"united"; "doc" acharia "documento"
            new Rule("TRANSFER_TED", "TRANSFER", METHOD_GENERIC, true,
                    List.of("ted", "doc ", "transferencia", "transferência")),
            new Rule("TRANSFER_BILLS", "TRANSFER", List.of("fatura", "boleto", "pagamento efetuado", "pagamento de titulo", "pagamento de título")),
            new Rule("TRANSFER_CASH", "TRANSFER", List.of("saque", "deposito", "depósito", "banco24horas", "banco 24 horas")),
            new Rule("TRANSFER_SELF", "TRANSFER", List.of("entre contas", "mesma titularidade", "conta propria", "conta própria"))
    );

    private static final List<Term> TERMS = RULES.stream()
            .flatMap(rule -> rule.keywords().stream().map(keyword -> new Term(
                    rule.systemKey(), rule.parentKey(), rule.priority(), keyword.length(),
                    rule.wholeWord() ? wordPattern(keyword) : null,
                    keyword)))
            .toList();

    /**
     * O espaço no fim de algumas keywords ("doc ", "vivo ") era um delimitador
     * improvisado — casar por palavra inteira faz melhor o mesmo trabalho, então
     * o termo é aparado antes de virar regex: "doc" pega "DOC RECEBIDO" e continua
     * fora de "DOCUMENTO".
     */
    private static Pattern wordPattern(String keyword) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword.trim()) + "(?![\\p{L}\\p{N}])");
    }

    /**
     * Vence a regra de maior prioridade e, dentro dela, a keyword MAIS LONGA que
     * aparece na descrição — não a primeira declarada. Sem o desempate por
     * comprimento, "mercado" (Alimentação) sequestra "mercado livre" (Compras) e
     * "uber" (Transporte) sequestra "uber eats" (Delivery).
     */
    public Optional<Hit> match(String description) {
        if (description == null) return Optional.empty();
        String lower = description.toLowerCase(java.util.Locale.ROOT);

        Hit best = null;
        int bestPriority = Integer.MIN_VALUE;
        int bestLength = 0;
        for (Term term : TERMS) {
            boolean wins = term.priority() > bestPriority
                    || (term.priority() == bestPriority && term.length() > bestLength);
            if (!wins || !term.matches(lower)) continue;
            best = new Hit(term.systemKey(), term.parentKey());
            bestPriority = term.priority();
            bestLength = term.length();
        }
        return Optional.ofNullable(best);
    }

    /** Só a raiz, para quem ainda fala o vocabulário antigo de 12 categorias. */
    public TransactionCategory categorize(String description, String type) {
        return match(description)
                .map(hit -> {
                    try {
                        return TransactionCategory.valueOf(hit.parentKey());
                    } catch (IllegalArgumentException e) {
                        return TransactionCategory.OTHER;
                    }
                })
                .orElseGet(() -> "CREDIT".equalsIgnoreCase(type)
                        ? TransactionCategory.INCOME
                        : TransactionCategory.OTHER);
    }

    /** Exposto para teste: garante que todo alvo do vocabulário existe no catálogo. */
    public List<String> allTargetKeys() {
        List<String> keys = new ArrayList<>();
        for (Rule rule : RULES) {
            keys.add(rule.systemKey());
            keys.add(rule.parentKey());
        }
        return keys;
    }
}
