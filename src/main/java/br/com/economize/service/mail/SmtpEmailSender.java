package br.com.economize.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "economize.mail.enabled", havingValue = "true")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${spring.mail.username:no-reply@economize.app}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Economize! — Recuperação de senha");
        message.setText("""
                Olá,

                Recebemos um pedido para redefinir a sua senha no Economize!.
                Para criar uma nova senha, acesse o link abaixo (válido por 30 minutos):

                %s

                Se você não fez esse pedido, ignore este e-mail — sua senha continua a mesma.
                """.formatted(resetLink));
        mailSender.send(message);
        log.info("E-mail de recuperação de senha enviado para {}", to);
    }

    @Override
    public void sendSecurityAlert(String to, String alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Economize! — Aviso de segurança");
        message.setText("""
                Olá,

                %s

                Este é um aviso automático. Nada foi bloqueado.
                """.formatted(alert));
        mailSender.send(message);
        log.info("Aviso de segurança enviado para {}", to);
    }
}
