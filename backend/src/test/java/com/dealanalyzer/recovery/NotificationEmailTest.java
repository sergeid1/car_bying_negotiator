package com.dealanalyzer.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class NotificationEmailTest {
  @Test void everyPurposeSkipsSuppressedRecipientsEvenWithoutAMailer() {
    @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    var suppression = mock(com.dealanalyzer.email.EmailSuppression.class);
    when(suppression.suppressed("buyer@example.com")).thenReturn(true);
    var email = new SmtpRecoveryEmail(provider, "company@example.com", suppression);
    for (var purpose : RecoveryEmail.Purpose.values()) email.send("buyer@example.com", "https://journey.example/", purpose);
    email.send("buyer@example.com", "https://journey.example/");
    verifyNoInteractions(provider);
  }
  @Test void everyNotificationUsesOnlyAFixedPromptAndSignInJourneyLink() {
    JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(sender);
    var email = new SmtpRecoveryEmail(provider, "service@example.com", mock(com.dealanalyzer.email.EmailSuppression.class));
    for (var type : com.dealanalyzer.notification.NotificationType.values())
      email.send("private.buyer@example.com", "https://journey.example/", RecoveryEmail.Purpose.valueOf(type.name()));
    var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender, times(5)).send(messages.capture());
    for (var message : messages.getAllValues()) {
      assertThat(message.getTo()).containsExactly("private.buyer@example.com");
      assertThat(message.getSubject()).isEqualTo("Your Buyer Account has an update");
      assertThat(message.getText()).contains("sign in", "https://journey.example/")
          .doesNotContain("private.buyer", "$", "VIN", "dealer", "#sign-in", "document");
      assertThat(message.getText().split("\n")).hasSize(3);
    }
  }
}
