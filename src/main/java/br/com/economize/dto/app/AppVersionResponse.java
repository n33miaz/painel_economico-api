package br.com.economize.dto.app;

/**
 * O que o app precisa saber sobre versões antes mesmo de fazer login.
 *
 * <p>{@code apiVersion} e {@code schemaVersion} são a resposta ao pedido de
 * alinhar app, API e banco: a primeira vem do build-info do jar em execução e
 * a segunda é a maior migration presente no classpath — a mesma que o Flyway
 * aplicou (ou vai aplicar) na subida. Quem olhar a tela "Sobre" do app e o
 * dashboard do Supabase enxerga o mesmo número.
 */
public record AppVersionResponse(
        /** Abaixo desta o app é bloqueado (426) e mandado atualizar. */
        String minVersion,
        /** A mais recente publicada — o app pode sugerir atualizar sem bloquear. */
        String latestVersion,
        /** Página do site com o APK/instruções. */
        String downloadUrl,
        /** Loja, quando houver listagem; nulo hoje. */
        String storeUrl,
        /** Texto da tela de atualização, escrito pelo operador. */
        String message,
        /** Versão da build da API ("dev" fora de um jar empacotado). */
        String apiVersion,
        /** Maior migration do classpath, ex.: "V23". */
        String schemaVersion
) {
}
