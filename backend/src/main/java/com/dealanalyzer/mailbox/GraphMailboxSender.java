package com.dealanalyzer.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Outlook and Microsoft 365 through Microsoft Graph with {@code Mail.Send} and {@code offline_access} only:
 * {@code sendMail}, saved to the Buyer's Sent Items. Enabling it outside testing needs a Microsoft Entra app
 * registration with publisher verification for {@code Mail.Send}. Microsoft offers no token revocation endpoint;
 * the Buyer can also remove the app from their Microsoft account.
 */
@Component
@ConditionalOnProperty(name = "mailbox.sender", havingValue = "live")
class GraphMailboxSender extends OAuthMailboxSender {
  private final ObjectMapper json = new ObjectMapper();

  GraphMailboxSender(@Value("${mailbox.microsoft.client-id:}") String clientId,
      @Value("${mailbox.microsoft.client-secret:}") String clientSecret) {
    super(clientId, clientSecret);
  }

  @Override public MailboxProvider provider() { return MailboxProvider.MICROSOFT; }
  @Override String authorizationEndpoint() { return "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"; }
  @Override String tokenEndpoint() { return "https://login.microsoftonline.com/common/oauth2/v2.0/token"; }
  @Override Map<String, String> authorizationParameters() { return Map.of("prompt", "select_account"); }

  @Override String address(JsonNode claims) {
    String email = claims.path("email").asText("");
    return email.isBlank() ? claims.path("preferred_username").asText(null) : email;
  }

  @Override public Delivery send(String accessToken, String from, OutgoingMessage message) {
    var body = Map.of("message", Map.of(
        "subject", message.subject(),
        "body", Map.of("contentType", "Text", "content", message.body()),
        "toRecipients", List.of(Map.of("emailAddress", Map.of("address", message.to())))),
        "saveToSentItems", true);
    try {
      return post("https://graph.microsoft.com/v1.0/me/sendMail", accessToken, "application/json", json.writeValueAsString(body));
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      return Delivery.REJECTED;
    }
  }
}
