package br.com.economize.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Achar o nome de uma pessoa na descrição de uma transferência.
 *
 * <p>É o sinal que sustenta duas varreduras diferentes, e por isso mora fora das
 * duas: {@code InternalTransferService} procura o nome do PRÓPRIO titular (o
 * dinheiro dele trocando de bolso) e {@code FamilyTransferService} procura o
 * nome de OUTRO membro da casa (o dinheiro que ficou dentro dela). A regra de
 * casamento tem de ser exatamente a mesma nas duas — se divergirem, a mesma
 * linha pode acabar marcada das duas formas, ou de nenhuma.
 */
public final class CounterpartyMatcher {

    /**
     * Só descrição de transferência entra na varredura. Uma compra num
     * estabelecimento que por acaso se chame como a pessoa não é transferência
     * para ela — e "MERCADO SILVA" existe.
     */
    public static final Pattern TRANSFER_LIKE = Pattern.compile(
            "\\b(pix|ted|doc|transferencia|transferido|deposito)\\b");

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * Partículas que não identificam ninguém: exigir "de", "dos" e "da" no meio
     * faria "Neemias Manso" (como o banco às vezes abrevia) deixar de casar.
     */
    private static final Set<String> PARTICLES = Set.of("de", "da", "do", "das", "dos", "e");

    private CounterpartyMatcher() {
    }

    /**
     * Todos os tokens do nome têm de estar presentes, cada um como palavra
     * inteira. Basta um faltar para não ser a pessoa: "Neemias Cormino Manso"
     * não casa com "Neemias Cormino Souza".
     */
    public static boolean carries(String normalized, List<String> tokens) {
        String padded = " " + normalized + " ";
        for (String token : tokens) {
            if (!padded.contains(" " + token + " ")) return false;
        }
        return true;
    }

    /**
     * Os pedaços do nome que servem para identificar alguém. Menos de dois
     * significa que não há sinal: um "Ana" solto casaria com qualquer Ana do
     * país, e quem chama decide o que fazer com isso.
     */
    public static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        if (name == null) return tokens;
        for (String piece : normalize(name).split(" ")) {
            // token de uma letra é inicial abreviada; partícula não identifica
            if (piece.length() < 2 || PARTICLES.contains(piece)) continue;
            tokens.add(piece);
        }
        return tokens;
    }

    /** Minúsculas, sem acento e sem pontuação — o extrato escreve de todo jeito. */
    public static String normalize(String value) {
        if (value == null) return "";
        String base = ACCENTS
                .matcher(Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        return SPACES.matcher(NON_WORD.matcher(base).replaceAll(" ")).replaceAll(" ").trim();
    }
}
