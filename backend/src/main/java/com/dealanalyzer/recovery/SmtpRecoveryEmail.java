package com.dealanalyzer.recovery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Component
class SmtpRecoveryEmail implements RecoveryEmail {
  private final ObjectProvider<JavaMailSender> sender;
  private final String from;
  SmtpRecoveryEmail(ObjectProvider<JavaMailSender> sender, @Value("${case.recovery.from:}") String from) {
    this.sender = sender; this.from = from;
  }
  public void send(String recipient, String link) {
    var mailer = sender.getIfAvailable();
    if (mailer == null || from.isBlank()) throw unavailable();
    var message = new SimpleMailMessage();
    message.setFrom(from); message.setTo(recipient); message.setSubject("Your Buying Case access link");
    message.setText("You requested Buying Case access. This single-use link expires in 15 minutes.\n"
        + link + "\nIf you did not request this, ignore this email.");
    try { mailer.send(message); } catch (org.springframework.mail.MailException failure) { throw unavailable(); }
  }
  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery unavailable; retry safely.");
  }
}
