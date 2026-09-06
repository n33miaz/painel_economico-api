package br.com.economize.service.mail;

/**
 * Abstracao do envio de e-mails transacionais. A implementacao ativa depende
 * de {@code economize.mail.enabled}: desligado usa {@link LogEmailSender}
 * (padrao, nao envia nada); ligado usa {@link SmtpEmailSender}.
 */
public interface EmailSender {

    void sendPasswordResetEmail(String to, String resetLink);

    /**
     * Aviso de evento de seguranca — acesso de rede nova, aparelho novo.
     *
     * <p>E AVISO, nao pedido de acao: nada e barrado por causa dele. Existe
     * porque a pessoa e a unica que sabe se aquele acesso foi ela.
     */
    void sendSecurityAlert(String to, String message);
}
