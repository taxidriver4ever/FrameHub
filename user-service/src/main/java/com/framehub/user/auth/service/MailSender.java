package com.framehub.user.auth.service;

public interface MailSender {
  void send(String email, String subject, String text);
}
