package com.framehub.user.auth.service;

import com.framehub.user.config.AppProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender implements MailSender {
  private final JavaMailSender mail;
  private final AppProperties p;

  public SmtpMailSender(JavaMailSender mail, AppProperties p) {
    this.mail = mail;
    this.p = p;
  }

  public void send(String email, String subject, String text) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(p.mailFrom());
    message.setTo(email);
    message.setSubject(subject);
    message.setText(text);
    mail.send(message);
  }
}
