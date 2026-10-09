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
  public void send(String recipient, String link) { send(recipient, link, Purpose.CASE_RECOVERY); }
  @Override public void send(String recipient, String link, Purpose purpose) {
    var mailer = sender.getIfAvailable();
    if (mailer == null || from.isBlank()) throw unavailable();
    var message = new SimpleMailMessage();
    message.setFrom(from); message.setTo(recipient);
    if (purpose == Purpose.ACCOUNT_SIGN_IN) {
      message.setSubject("Your otdog sign-in link");
      message.setText("You asked to sign in to your Buyer Account. This single-use link expires in 15 minutes.\n"
          + link + "\nIf you did not request this, ignore this email. No account is used until the link is opened.");
    } else if (purpose == Purpose.CASE_RECOVERY) {
      message.setSubject("Your Buying Case access link");
      message.setText("You requested Buying Case access. This single-use link expires in 15 minutes.\n"
          + link + "\nIf you did not request this, ignore this email.");
    } else {
      message.setSubject("Your Buyer Account has an update");
      String prompt = switch (purpose) {
        case DEALER_REPLY -> "A reply is ready to review.";
        case DRAFT_NEEDS_APPROVAL -> "A draft needs your approval.";
        case MANDATE_EXPIRING -> "Your negotiation mandate is about to expire.";
        case APPOINTMENT -> "An appointment update is ready to review.";
        default -> "An account-security update is ready to review.";
      };
      message.setText(prompt + "\nOpen your journey and sign in to review it.\n" + link);
    }
    try { mailer.send(message); } catch (org.springframework.mail.MailException failure) { throw unavailable(); }
  }
  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery unavailable; retry safely.");
  }
}
