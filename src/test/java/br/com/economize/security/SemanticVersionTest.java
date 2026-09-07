package br.com.economize.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A comparação de versão que decide o 426. O erro que este teste existe para
 * impedir é o textual: "2.10.0" &lt; "2.9.9" na ordem alfabética.
 */
class SemanticVersionTest {

    @Test
    @DisplayName("2.10.0 é MAIOR que 2.9.9 — compara número a número, não texto")
    void minorDeDoisDigitosVenceMinorDeUm() {
        SemanticVersion v2_10 = SemanticVersion.parse("2.10.0").orElseThrow();
        SemanticVersion v2_9_9 = SemanticVersion.parse("2.9.9").orElseThrow();

        assertThat(v2_10).isGreaterThan(v2_9_9);
        assertThat(v2_9_9.isOlderThan(v2_10)).isTrue();
        assertThat(v2_10.isOlderThan(v2_9_9)).isFalse();
    }

    @Test
    @DisplayName("Ordem lexicográfica dos três campos: major manda, depois minor, depois patch")
    void ordemDosCampos() {
        SemanticVersion base = SemanticVersion.parse("2.2.0").orElseThrow();

        assertThat(SemanticVersion.parse("1.99.99").orElseThrow().isOlderThan(base)).isTrue();
        assertThat(SemanticVersion.parse("2.1.9").orElseThrow().isOlderThan(base)).isTrue();
        assertThat(SemanticVersion.parse("2.2.0").orElseThrow().isOlderThan(base)).isFalse();
        assertThat(SemanticVersion.parse("2.2.1").orElseThrow().isOlderThan(base)).isFalse();
        assertThat(SemanticVersion.parse("3.0.0").orElseThrow().isOlderThan(base)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.2.0-beta.1", "2.2.0+7", "v2.2.0", " 2.2.0 ", "2.2.0-rc1+build.9"})
    @DisplayName("Sufixo de build, prefixo v e espaços não mudam o número")
    void sufixosSaoIgnorados(String raw) {
        assertThat(SemanticVersion.parse(raw)).contains(new SemanticVersion(2, 2, 0));
    }

    @Test
    @DisplayName("Versão curta completa com zero: 2.2 é 2.2.0 e 3 é 3.0.0")
    void versaoCurtaCompletaComZero() {
        assertThat(SemanticVersion.parse("2.2")).contains(new SemanticVersion(2, 2, 0));
        assertThat(SemanticVersion.parse("3")).contains(new SemanticVersion(3, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "2.x.0", "2..0", "1.2.3.4", "-1.0.0", "2.2.0 beta"})
    @DisplayName("O que não é versão volta vazio — nunca exceção, nunca zero")
    void lixoVoltaVazio(String raw) {
        assertThat(SemanticVersion.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("Nulo também volta vazio")
    void nuloVoltaVazio() {
        assertThat(SemanticVersion.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("toString devolve o formato canônico, que é o que vai no ProblemDetail")
    void toStringCanonico() {
        assertThat(SemanticVersion.parse("v2.10-beta").orElseThrow()).hasToString("2.10.0");
    }
}
