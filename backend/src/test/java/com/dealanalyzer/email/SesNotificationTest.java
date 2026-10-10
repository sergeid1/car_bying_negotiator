package com.dealanalyzer.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class SesNotificationTest {
  private static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:feedback";
  private static final ObjectMapper JSON = new ObjectMapper();
  private KeyPair key;
  private SnsTransport transport;
  private EmailSuppression suppression;
  private SesNotificationController controller;
  @BeforeEach void setup() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
    transport = mock(SnsTransport.class);
    when(transport.certificate(any())).thenReturn(key.getPublic());
    suppression = mock(EmailSuppression.class);
    controller = new SesNotificationController(JSON, new SnsVerifier(TOPIC, transport), suppression);
  }
  private ObjectNode envelope(String type, String message) {
    var node = JSON.createObjectNode();
    node.put("Type", type).put("TopicArn", TOPIC).put("MessageId", "fixture-id")
        .put("Timestamp", "2026-10-09T00:00:00Z").put("Message", message).put("SignatureVersion", "2")
        .put("SigningCertURL", "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-test.pem");
    return node;
  }
  private ObjectNode sign(ObjectNode node) throws Exception {
    var signature = Signature.getInstance(node.path("SignatureVersion").asText().equals("1") ? "SHA1withRSA" : "SHA256withRSA");
    signature.initSign(key.getPrivate());
    // Fixed AWS field order, independent of the production canonical builder.
    String[] fields = node.path("Type").asText().equals("Notification")
        ? new String[]{"Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type"}
        : new String[]{"Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type"};
    String text = "";
    for (String field : fields) if (node.has(field)) text += field + "\n" + node.get(field).asText() + "\n";
    signature.update(text.getBytes(StandardCharsets.UTF_8));
    node.put("Signature", Base64.getEncoder().encodeToString(signature.sign())); return node;
  }
  private void receive(ObjectNode node) throws Exception {
    var request = new MockHttpServletRequest(); request.setContent(JSON.writeValueAsBytes(node)); controller.receive(request);
  }
  private static String bounce(String type) {
    return "{\"notificationType\":\"Bounce\",\"bounce\":{\"bounceType\":\"" + type
        + "\",\"bouncedRecipients\":[{\"emailAddress\":\"buyer@example.com\"}]}}";
  }
  @Test void signedPermanentBounceAndComplaintSuppressButTransientDoesNot() throws Exception {
    var permanent = sign(envelope("Notification", bounce("Permanent")).put("Subject", "escaped\nsubject"));
    receive(permanent); receive(permanent);
    receive(sign(envelope("Notification", bounce("Transient"))));
    receive(sign(envelope("Notification", "{\"notificationType\":\"Complaint\",\"complaint\":{\"complainedRecipients\":[{\"emailAddress\":\"buyer@example.com\"}]}}")
        .put("SignatureVersion", "1")));
    verify(suppression, times(2)).suppress("buyer@example.com", "BOUNCE");
    verify(suppression).suppress("buyer@example.com", "COMPLAINT");
    verifyNoMoreInteractions(suppression);
  }
  @Test void forgedMalformedAndUnapprovedMessagesAreRefusedBeforeSesParsing() throws Exception {
    var forged = sign(envelope("Notification", bounce("Permanent"))); forged.put("Message", "not-json");
    assertThatThrownBy(() -> receive(forged)).isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
    var unauthorized = sign(envelope("Notification", "not-json").put("TopicArn", TOPIC + "-other"));
    clearInvocations(transport);
    assertThatThrownBy(() -> receive(unauthorized)).hasMessageContaining("403");
    verifyNoInteractions(transport);
    for (String url : new String[]{"http://sns.us-east-1.amazonaws.com/SimpleNotificationService-test.pem",
        "https://sns.us-east-1.amazonaws.com.evil.test/SimpleNotificationService-test.pem",
        "https://sns.us-west-2.amazonaws.com/SimpleNotificationService-test.pem",
        "https://sns.us-east-1.amazonaws.com:8443/SimpleNotificationService-test.pem",
        "https://sns.us-east-1.amazonaws.com/other.pem", "https://localhost/cert.pem"}) {
      var node = sign(envelope("Notification", "not-json").put("SigningCertURL", url));
      assertThatThrownBy(() -> receive(node)).hasMessageContaining("403");
    }
    verifyNoInteractions(transport, suppression);
  }
  @Test void onlySignedConfiguredSubscriptionsAreConfirmedToTheTrustedHost() throws Exception {
    var node = envelope("SubscriptionConfirmation", "confirm\nsubscription")
        .put("Token", "token+value").put("SubscribeURL", "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription");
    receive(sign(node));
    verify(transport).confirm(URI.create("https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=arn%3Aaws%3Asns%3Aus-east-1%3A123456789012%3Afeedback&Token=token%2Bvalue"));
    clearInvocations(transport);
    node.put("Token", "forged");
    assertThatThrownBy(() -> receive(node)).hasMessageContaining("403");
    verify(transport, never()).confirm(any());
    node.put("SubscribeURL", "https://evil.test/");
    assertThatThrownBy(() -> receive(sign(node))).hasMessageContaining("403");
    verify(transport, never()).confirm(any());
    verifyNoInteractions(suppression);
  }
  @Test void oversizedMessagesAreRefusedWithoutFetchingCertificates() throws Exception {
    var request = new MockHttpServletRequest(); request.setContent(new byte[262145]);
    assertThatThrownBy(() -> controller.receive(request)).hasMessageContaining("413");
    verifyNoInteractions(transport, suppression);
  }
  @Test void temporaryCertificateFailureAllowsSnsToRetryWithoutProcessingContent() throws Exception {
    when(transport.certificate(any())).thenThrow(new SnsTransport.Unavailable());
    var node = sign(envelope("Notification", bounce("Permanent")));
    assertThatThrownBy(() -> receive(node)).hasMessageContaining("503");
    verifyNoInteractions(suppression);
  }
}
