package br.com.economize.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "economize.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        log.warn("Envio de e-mail desabilitado (economize.mail.enabled=false): "
                + "link de recuperação de senha gerado para {} mas nenhum e-mail foi enviado", to);
        // O link carrega o token de reset: só pode aparecer em DEBUG, nunca em INFO
        log.debug("Link de recuperação de senha para {}: {}", to, resetLink);
    }

    @Override
    public void sendSecurityAlert(String to, String message) {
        // WARN e não DEBUG: um aviso de segurança que ninguém recebeu é
        // exatamente o tipo de coisa que precisa aparecer no log de produção
        log.warn("Envio de e-mail desabilitado (economize.mail.enabled=false): aviso de segurança "
                + "para {} NÃO foi enviado — {}", to, message);
    }
}
