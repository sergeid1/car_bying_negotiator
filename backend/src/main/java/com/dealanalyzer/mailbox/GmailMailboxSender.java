package com.dealanalyzer.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gmail with the {@code gmail.send} scope only: {@code users.messages.send} with an RFC 5322 message. Sent mail
 * appears in the Buyer's Sent folder. Enabling it outside testing needs a Google Cloud project with a verified
 * OAuth consent screen for {@code gmail.send}.
 */
@Component
@ConditionalOnProperty(name = "mailbox.sender", havingValue = "live")
class GmailMailboxSender extends OAuthMailboxSender {
  GmailMailboxSender(@Value("${mailbox.google.client-id:}") String clientId,
      @Value("${mailbox.google.client-secret:}") String clientSecret) {
    super(clientId, clientSecret);
  }

  @Override public MailboxProvider provider() { return MailboxProvider.GOOGLE; }
  @Override String authorizationEndpoint() { return "https://accounts.google.com/o/oauth2/v2/auth"; }
  @Override String tokenEndpoint() { return "https://oauth2.googleapis.com/token"; }
  @Override Map<String, String> authorizationParameters() {
    return Map.of("access_type", "offline", "prompt", "consent", "include_granted_scopes", "false");
  }

  @Override String address(JsonNode claims) {
    return claims.path("email_verified").asBoolean(false) ? claims.path("email").asText(null) : null;
  }

  @Override public Delivery send(String accessToken, String from, OutgoingMessage message) {
    String raw;
    try {
      raw = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(Rfc5322Message.render(from, message).getBytes(StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException unsafeHeader) {
      return Delivery.REJECTED;
    }
    return post("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", accessToken, "application/json",
        "{\"raw\":\"" + raw + "\"}");
  }

  @Override public void revoke(String refreshToken) {
    bestEffort("https://oauth2.googleapis.com/revoke", form(Map.of("token", refreshToken)));
  }
}
