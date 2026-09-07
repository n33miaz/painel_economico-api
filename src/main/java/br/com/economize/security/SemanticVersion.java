package br.com.economize.security;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versão do aplicativo no formato MAJOR.MINOR.PATCH, comparável como número.
 *
 * <p>Comparar como texto é a armadilha óbvia desta feature: "2.10.0" vem ANTES
 * de "2.9.9" na ordem alfabética, e a décima versão menor bloquearia justamente
 * quem acabou de atualizar. Por isso os três campos são inteiros.
 *
 * <p>Sufixos ({@code -beta.1}, {@code +7}) são ignorados de propósito: o EAS
 * carimba identificador de build sem mudar o contrato com a API, e um sufixo
 * inesperado não pode virar bloqueio. O "v" inicial também é tolerado — é o
 * que sai de {@code git describe}.
 */
public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    // "2.2", "2.2.0", "v2.2.0", "2.2.0-beta.1", "2.2.0+7" entram; lixo não
    private static final Pattern PATTERN =
            Pattern.compile("^v?(\\d{1,5})(?:\\.(\\d{1,5}))?(?:\\.(\\d{1,5}))?(?:[-+].*)?$");

    /** Vazio quando o texto não é uma versão — quem chama decide o que fazer. */
    public static Optional<SemanticVersion> parse(String raw) {
        if (raw == null) return Optional.empty();
        Matcher matcher = PATTERN.matcher(raw.trim());
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))));
    }

    public boolean isOlderThan(SemanticVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) return byMajor;
        int byMinor = Integer.compare(minor, other.minor);
        if (byMinor != 0) return byMinor;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
