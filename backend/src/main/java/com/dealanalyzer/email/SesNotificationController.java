package com.dealanalyzer.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public provider callback, authenticated by SNS signature rather than a Buyer credential. */
@RestController
public class SesNotificationController {
  private final ObjectMapper mapper;
  private final SnsVerifier verifier;
  private final EmailSuppression suppression;
  public SesNotificationController(ObjectMapper mapper, SnsVerifier verifier, EmailSuppression suppression) {
    this.mapper = mapper; this.verifier = verifier; this.suppression = suppression;
  }
  @PostMapping("/api/v1/email/ses-notifications")
  public void receive(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(262145);
    if (bytes.length > 262144) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "SNS message too large.");
    com.fasterxml.jackson.databind.JsonNode envelope;
    try {
      envelope = mapper.readTree(bytes);
      verifier.verify(envelope);
    } catch (SnsTransport.Unavailable unavailable) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SNS verification unavailable.");
    } catch (Exception refused) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SNS message refused.");
    }
    if (SnsVerifier.required(envelope, "Type").equals("SubscriptionConfirmation")) {
      try { verifier.confirm(envelope); }
      catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SNS confirmation unavailable."); }
      return;
    }
    // Only authenticated, allowlisted messages reach the SES parser. Never persist the payload.
    try {
      var notification = mapper.readTree(SnsVerifier.required(envelope, "Message"));
      String type = SnsVerifier.required(notification, "notificationType");
      var recipients = type.equals("Complaint") ? notification.path("complaint").path("complainedRecipients")
          : type.equals("Bounce") && notification.path("bounce").path("bounceType").asText().equals("Permanent")
          ? notification.path("bounce").path("bouncedRecipients") : null;
      if (recipients == null) return; // Transient bounces and delivery events do not suppress.
      if (!recipients.isArray() || recipients.isEmpty()) throw new IllegalArgumentException("Recipients required");
      for (var recipient : recipients) {
        String address = SnsVerifier.required(recipient, "emailAddress");
        if (address.length() > 254 || !address.contains("@")) throw new IllegalArgumentException("Invalid recipient");
        suppression.suppress(address, type.equals("Complaint") ? "COMPLAINT" : "BOUNCE");
      }
    } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SES notification refused.");
    }
  }
}
