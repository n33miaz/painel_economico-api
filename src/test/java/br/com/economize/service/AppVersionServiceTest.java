package br.com.economize.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A versão do schema anunciada ao app é a MAIOR migration do classpath — lida,
 * não escrita à mão, para não ficar para trás na próxima V.
 */
class AppVersionServiceTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    @DisplayName("schemaVersion é a maior V dos arquivos de migration do projeto")
    void schemaVersionEAMaiorMigrationDoClasspath() throws IOException {
        int esperado;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            esperado = files.map(p -> NAME.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }

        AppVersionService service = new AppVersionService(emptyBuild(), "2.2.0", "2.2.0",
                "https://economize-web.onrender.com/baixar", "", "msg");

        assertThat(service.schemaVersion()).isEqualTo("V" + esperado);
        // e esta rodada entregou a V23: se o número abaixo ficar menor que o
        // esperado, alguém apagou uma migration
        assertThat(esperado).isGreaterThanOrEqualTo(23);
    }

    @Test
    @DisplayName("A maior é pelo NÚMERO: V9 não vence V22 só porque '9' > '2'")
    void maiorPorNumeroENaoPorTexto() {
        String maior = AppVersionService.highestMigration(Stream.of(
                "V9__x.sql", "V22__create_trusted_devices.sql", "V10__y.sql", "V1__create_initial_schema.sql"));

        assertThat(maior).isEqualTo("V22");
    }

    @Test
    @DisplayName("Arquivo que não segue o padrão do Flyway é ignorado; sem nenhum, responde 'unknown'")
    void ignoraOQueNaoEMigration() {
        assertThat(AppVersionService.highestMigration(Stream.of("README.md", "R__repeatable.sql", null)))
                .isEqualTo(AppVersionService.UNKNOWN_SCHEMA);
        assertThat(AppVersionService.highestMigration(Stream.of("V3__a.sql", "notas.txt"))).isEqualTo("V3");
    }

    @Test
    @DisplayName("Falha ao listar o classpath não derruba a subida: vira 'unknown' com aviso")
    void falhaDeLeituraViraUnknown() throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenThrow(new IOException("disco sumiu"));

        assertThat(AppVersionService.resolveSchemaVersion(resolver)).isEqualTo(AppVersionService.UNKNOWN_SCHEMA);
    }

    @Test
    @DisplayName("Sem build-info (mvn spring-boot:run, fatia de teste) a versão da API é 'dev'")
    void semBuildInfoEDev() {
        AppVersionService service = new AppVersionService(emptyBuild(), "2.2.0", "2.3.0",
                "https://d", "", "msg");

        var response = service.describe();
        assertThat(response.apiVersion()).isEqualTo(AppVersionService.DEV_VERSION);
        assertThat(response.minVersion()).isEqualTo("2.2.0");
        assertThat(response.latestVersion()).isEqualTo("2.3.0");
        assertThat(response.storeUrl()).as("loja vazia vira null, não string vazia").isNull();
        assertThat(response.schemaVersion()).startsWith("V");
    }

    @Test
    @DisplayName("Com build-info a versão da API é a do jar, e a loja preenchida é repassada")
    void comBuildInfoUsaAVersaoDaBuild() {
        Properties props = new Properties();
        props.setProperty("version", "1.4.0");
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));

        AppVersionService service = new AppVersionService(provider, "2.2.0", "2.2.0", "https://d",
                " https://play.google.com/store/apps/details?id=app.economize ", "msg");

        assertThat(service.apiVersion()).isEqualTo("1.4.0");
        assertThat(service.describe().storeUrl())
                .isEqualTo("https://play.google.com/store/apps/details?id=app.economize");
    }

    @Test
    @DisplayName("O resolver real encontra as migrations do classpath de teste")
    void resolverRealEncontraMigrations() {
        String schema = AppVersionService.resolveSchemaVersion(
                new PathMatchingResourcePatternResolver(getClass().getClassLoader()));

        assertThat(schema).matches("V\\d+");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> emptyBuild() {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
