package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dealanalyzer.email.EmailSuppression;
import com.dealanalyzer.email.SnsTransport;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

class SesDeliveryAcceptanceTest {
  private static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:feedback";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final CopyOnWriteArrayList<SimpleMailMessage> SENT = new CopyOnWriteArrayList<>();
  private static final KeyPair KEY = testKey();
  @TempDir Path directory;
  private static KeyPair testKey() {
    try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    catch (Exception failure) { throw new IllegalStateException(failure); }
  }
  @TestConfiguration static class Providers {
    @Bean @Primary SnsTransport fakeSnsTransport() throws Exception {
      var transport = mock(SnsTransport.class); when(transport.certificate(any())).thenReturn(KEY.getPublic()); return transport;
    }
    @Bean @Primary JavaMailSender recordingMailer() {
      var mailer = mock(JavaMailSender.class);
      doAnswer(invocation -> { SENT.add(new SimpleMailMessage(invocation.getArgument(0, SimpleMailMessage.class))); return null; })
          .when(mailer).send(any(SimpleMailMessage.class));
      return mailer;
    }
  }
  @Test void signedHttpFeedbackSkipsAllSendsAndKeepsPublicAcknowledgements() throws Exception {
    SENT.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("ses", false, Providers.class,
        "--case.recovery.from=service@company.example", "--email.ses.topic-arns=" + TOPIC,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--security.request-email.peer-requests=100")) {
      var client = app.client();
      var initial = post(client, "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "initial");
      assertThat(SENT).hasSize(1);
      assertThat(SENT.getFirst().getFrom()).isEqualTo("service@company.example");
      String session = AccountSessionTestSupport.verify(client, SENT.getFirst().getText().split("#sign-in=")[1].split("\n")[0]);
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      String accountId = jdbc.queryForObject("select id from buyer_accounts", String.class);
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String caseId = created.at("/case/id").asText(), caseToken = created.path("accessToken").asText();
      post(client, "/api/v1/cases/" + caseId + "/recovery-email", Map.of("email", "buyer@example.com"), "enroll", caseToken);
      var recovery = post(client, "/api/v1/recovery/requests", Map.of("caseId", caseId, "email", "buyer@example.com"), "before");
      int delivered = SENT.size();
      assertThat(callback(client, signedBounce())).isEqualTo(200);
      assertThat(callback(client, signedBounce())).isEqualTo(200);
      assertThat(app.context().getBean(EmailSuppression.class).suppressed("Buyer@Example.com")).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from email_suppressions", Long.class)).isEqualTo(1);
      assertThat(post(client, "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "after")).isEqualTo(initial);
      assertThat(post(client, "/api/v1/recovery/requests", Map.of("caseId", caseId, "email", "buyer@example.com"), "after-recovery")).isEqualTo(recovery);
      app.context().getBean(BuyerNotifications.class).notify(accountId, NotificationType.ACCOUNT_SECURITY, "security-event");
      assertThat(SENT).hasSize(delivered);
      assertThat(session).isNotBlank();
      assertThat(jdbc.queryForList("select * from email_suppressions").toString()).doesNotContain("buyer@example.com");
    }
  }
  @Test void callbackIsCoveredByApiPeerRateLimitAndPrivacyHeaders() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("ses-limit", false, Providers.class,
        "--security.rate-limit.requests=1", "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      assertThat(callback(app.client(), "{}")).isEqualTo(403);
      int status = app.client().post().uri("/api/v1/email/ses-notifications").contentType(MediaType.TEXT_PLAIN).body("{}")
          .exchange((request, response) -> {
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
            return response.getStatusCode().value();
          });
      assertThat(status).isEqualTo(429);
      verifyNoInteractions(app.context().getBean("fakeSnsTransport", SnsTransport.class));
    }
  }
  private static int callback(RestClient client, String body) {
    return client.post().uri("/api/v1/email/ses-notifications").contentType(MediaType.TEXT_PLAIN).body(body)
        .exchange((request, response) -> response.getStatusCode().value());
  }
  private static String signedBounce() throws Exception {
    var node = JSON.createObjectNode().put("Type", "Notification").put("TopicArn", TOPIC)
        .put("MessageId", "test-event").put("Timestamp", "2026-10-09T00:00:00Z").put("SignatureVersion", "2")
        .put("SigningCertURL", "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-test.pem")
        .put("Message", "{\"notificationType\":\"Bounce\",\"bounce\":{\"bounceType\":\"Permanent\",\"bouncedRecipients\":[{\"emailAddress\":\"buyer@example.com\"}]}}");
    String canonical = "";
    for (String field : new String[]{"Message", "MessageId", "Timestamp", "TopicArn", "Type"})
      canonical += field + "\n" + node.get(field).asText() + "\n";
    var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(KEY.getPrivate());
    signature.update(canonical.getBytes(StandardCharsets.UTF_8));
    node.put("Signature", Base64.getEncoder().encodeToString(signature.sign())); return JSON.writeValueAsString(node);
  }
}
