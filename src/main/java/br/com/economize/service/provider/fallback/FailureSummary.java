package br.com.economize.service.provider.fallback;

/**
 * Uma linha de log por falha de fonte externa, sem pilha e sem corpo inteiro
 * de resposta. A pilha de um 429 não diz nada que o status já não diga, e o
 * corpo do provedor pode ter centenas de caracteres — o log do Render é curto
 * e é lido por gente.
 */
public final class FailureSummary {

    private static final int MAX_LENGTH = 160;

    private FailureSummary() {
    }

    public static String of(Throwable error) {
        if (error == null) {
            return "erro desconhecido";
        }
        String message = error.getMessage();
        String text = message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "…";
    }
}
