package com.dealanalyzer.mailbox;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ticket 83's provider boundary: send-only scopes, the Gmail message format, and token protection. */
class MailboxBoundaryTest {
  @Test void onlySendAndIdentityScopesAreAccepted() {
    var google = MailboxProvider.GOOGLE;
    assertThat(google.sendOnly(Set.of("openid", "email", "https://www.googleapis.com/auth/gmail.send"))).isTrue();
    assertThat(google.grantsSend(Set.of("https://www.googleapis.com/auth/gmail.send"))).isTrue();
    for (String read : Set.of("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.metadata", "https://mail.google.com/"))
      assertThat(google.sendOnly(Set.of("https://www.googleapis.com/auth/gmail.send", read))).as(read).isFalse();
    assertThat(google.grantsSend(Set.of("openid", "email"))).isFalse();

    var microsoft = MailboxProvider.MICROSOFT;
    assertThat(microsoft.sendOnly(Set.of("https://graph.microsoft.com/Mail.Send", "offline_access", "openid", "User.Read"))).isTrue();
    assertThat(microsoft.grantsSend(Set.of("Mail.Send"))).isTrue();
    for (String read : Set.of("Mail.Read", "https://graph.microsoft.com/Mail.ReadWrite", "Mail.ReadBasic", "Mail.Send.Shared"))
      assertThat(microsoft.sendOnly(Set.of("Mail.Send", read))).as(read).isFalse();
    assertThat(MailboxProvider.GOOGLE.requestedScope()).doesNotContain("readonly", "modify");
    assertThat(MailboxProvider.MICROSOFT.requestedScope()).contains("Mail.Send", "offline_access").doesNotContain("Read");
  }

  @Test void gmailMessageCarriesTheExactTextAndRefusesHeaderInjection() {
    String body = "Hello,\nCould you send an itemized quote? Café — $25,000.\n\nThanks";
    String raw = Rfc5322Message.render("buyer@example.com",
        new MailboxSender.OutgoingMessage("sales@dealer.example", "Quote for the Accord — thanks", body));
    assertThat(raw).startsWith("From: buyer@example.com\r\nTo: sales@dealer.example\r\nSubject: =?UTF-8?B?")
        .contains("Content-Type: text/plain; charset=UTF-8\r\n", "Content-Transfer-Encoding: base64\r\n");
    String encoded = raw.substring(raw.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
    assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)).isEqualTo(body.replace("\n", "\r\n"));
    String subject = raw.lines().filter(line -> line.startsWith("Subject: ")).findFirst().orElseThrow();
    assertThat(new String(Base64.getDecoder().decode(subject.substring(19, subject.length() - 2)), StandardCharsets.UTF_8))
        .isEqualTo("Quote for the Accord — thanks");
    assertThatThrownBy(() -> Rfc5322Message.render("buyer@example.com",
        new MailboxSender.OutgoingMessage("sales@dealer.example\r\nBcc: someone@example.com", "Subject", body)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void tokensAreBoundToTheirRowAndPurposeAndNeedTheDedicatedKey() {
    var protection = new MailboxTokenProtection("k".repeat(32));
    String stored = protection.encrypt("row-1", MailboxConnections.REFRESH, "refresh-secret");
    assertThat(stored).doesNotContain("refresh-secret");
    assertThat(protection.decrypt("row-1", MailboxConnections.REFRESH, stored)).isEqualTo("refresh-secret");
    assertThatThrownBy(() -> protection.decrypt("row-2", MailboxConnections.REFRESH, stored)).hasMessageNotContaining("refresh-secret");
    assertThatThrownBy(() -> protection.decrypt("row-1", MailboxConnections.ACCESS, stored)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new MailboxTokenProtection("short").encrypt("row-1", "x", "y"))
        .hasMessageContaining("MAILBOX_TOKEN_ENCRYPTION_KEY");
  }

  @Test void theFakeBindsItsCodeToThePkceVerifier() {
    var fake = new FakeMailboxSender(MailboxProvider.GOOGLE);
    String verifier = "v".repeat(43);
    String url = fake.authorizationUrl("state-1", Pkce.challenge(verifier), "http://localhost:5173/");
    String code = java.net.URLDecoder.decode(url.substring(url.indexOf("code=") + 5, url.indexOf("&state=")), StandardCharsets.UTF_8);
    assertThatThrownBy(() -> fake.exchange(code, "w".repeat(43), "http://localhost:5173/")).isInstanceOf(MailboxSender.MailboxFailure.class);
    assertThat(fake.exchange(code, verifier, "http://localhost:5173/").address()).isEqualTo(FakeMailboxSender.ADDRESS);
    assertThat(fake.exchange(code, verifier, "x").toString()).isEqualTo("Grant[redacted]");
  }
}
