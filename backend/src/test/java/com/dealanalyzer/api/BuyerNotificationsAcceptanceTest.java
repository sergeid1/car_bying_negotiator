package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class BuyerNotificationsAcceptanceTest {
  @TempDir Path directory;
  static final CopyOnWriteArrayList<Mail> sent = new CopyOnWriteArrayList<>();
  static volatile boolean fail;
  record Mail(String recipient, String link, RecoveryEmail.Purpose purpose) {}
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail notificationsEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { send(recipient, link, Purpose.CASE_RECOVERY); }
        public void send(String recipient, String link, Purpose purpose) {
          if (fail) throw new IllegalStateException("private.buyer@example.com provider payload");
          sent.add(new Mail(recipient, link, purpose));
        }
      };
    }
  }

  @Test void preferencesDeliveryPrivacyConcurrencyAndAccountLifecycle(CapturedOutput output) throws Exception {
    sent.clear(); fail = false;
    String id, session;
    try (var app = start("notifications")) {
      var client = app.client();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      var notifications = app.context().getBean(BuyerNotifications.class);
      session = signIn(client, "private.buyer@example.com");
      id = jdbc.queryForObject("select id from buyer_accounts", String.class);
      sent.clear();
      assertThat(request(client, "GET", null, null).status()).isEqualTo(401);
      assertThat(request(client, "GET", "wrong", null).status()).isEqualTo(401);
      assertThat(request(client, "PUT", null, Map.of("type", "ACCOUNT_SECURITY", "enabled", false)).status()).isEqualTo(401);
      assertThat(request(client, "GET", session, null).body()).hasSize(5);
      assertThat(request(client, "PUT", session, Map.of("type", "ACCOUNT_SECURITY", "enabled", false)).status()).isEqualTo(400);
      assertThat(request(client, "PUT", session, Map.of("type", "DEALER_REPLY")).status()).isEqualTo(400);
      for (var type : NotificationType.values()) {
        if (type.mandatory()) continue;
        assertThat(request(client, "PUT", session, Map.of("type", type, "enabled", false)).status()).isEqualTo(200);
        notifications.notify(id, type, "off-" + type);
        assertThat(request(client, "PUT", session, Map.of("type", type, "enabled", true)).status()).isEqualTo(200);
      }
      assertThat(sent).isEmpty();
      assertThat(jdbc.queryForObject("select count(*) from buyer_notification_deliveries where state = 'OPTED_OUT'", Long.class)).isEqualTo(4);
      // All contenders use one persisted event, even across concurrent producer calls.
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 6; i++) jobs.add(pool.submit(() -> {
          try { start.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
          notifications.notify(id, NotificationType.DEALER_REPLY, "event-with-sensitive-text-12345");
        }));
        start.countDown();
        for (var job : jobs) job.get();
      }
      assertThat(sent).hasSize(1);
      assertThat(sent.getFirst().recipient()).isEqualTo("private.buyer@example.com");
      assertThat(sent.getFirst().link()).isEqualTo("http://localhost:5173/");
      // The emailed URL has no credential; account data is protected by sign-in.
      int unsignedStatus = client.get().uri("/api/v1/account").exchange((req, res) -> res.getStatusCode().value());
      assertThat(unsignedStatus).isEqualTo(401);
      notifications.notify(id, NotificationType.DRAFT_NEEDS_APPROVAL, "draft");
      notifications.notify(id, NotificationType.MANDATE_EXPIRING, "expiry");
      notifications.notify(id, NotificationType.APPOINTMENT, "appointment");
      notifications.notify(id, NotificationType.APPOINTMENT, "over-limit");
      assertThat(sent).hasSize(4);
      assertThat(jdbc.queryForObject("select count(*) from buyer_notification_deliveries where state = 'RATE_LIMITED'", Long.class)).isEqualTo(1);
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "security");
      assertThat(sent).hasSize(5);
      // The per-account limit does not consume another Buyer's capacity.
      String other = signIn(client, "other@example.com");
      String otherId = jdbc.queryForObject("select id from buyer_accounts where id <> ?", String.class, id);
      notifications.notify(otherId, NotificationType.APPOINTMENT, "other-event");
      assertThat(sent.getLast().purpose()).isEqualTo(RecoveryEmail.Purpose.APPOINTMENT);
      fail = true;
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "failed-event");
      fail = false;
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "failed-event");
      assertThat(jdbc.queryForObject("select count(*) from buyer_notification_deliveries where state = 'FAILED'", Long.class)).isEqualTo(1);
      // Separate mandatory quota is still bounded.
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "security-3");
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "security-4");
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "security-5");
      assertThat(jdbc.queryForObject("select count(*) from buyer_notification_deliveries where state = 'RATE_LIMITED'", Long.class)).isEqualTo(2);
      jdbc.update("update buyer_notification_deliveries set created_at = ? where account_id = ?",
          java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2))), id);
      int beforeWindow = sent.size();
      notifications.notify(id, NotificationType.APPOINTMENT, "new-window");
      assertThat(sent).hasSize(beforeWindow + 1);
      // An uncertain adapter outcome remains deduplicated after restart too.
      jdbc.update("update buyer_notification_deliveries set state = 'DELIVERY_UNKNOWN', completed_at = null where account_id = ? and notification_type = 'DEALER_REPLY' and state = 'SENT'", id);
      request(client, "PUT", session, Map.of("type", "APPOINTMENT", "enabled", false));
      byte[] archive = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session)).header("Account-Request", "1")
          .retrieve().body(byte[].class);
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
        boolean found = false;
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (!entry.getName().equals("account.json")) continue;
          var json = new ObjectMapper().readTree(zip.readAllBytes());
          assertThat(json.path("notificationPreferences")).hasSize(5);
          assertThat(json.path("notificationDeliveries").size()).isGreaterThan(5);
          assertThat(json.toString()).doesNotContain("dedupe_hash", "event-with-sensitive-text", "http://localhost");
          found = true;
        }
        assertThat(found).isTrue();
      }
      for (String table : java.util.List.of("buyer_notification_preferences", "buyer_notification_deliveries"))
        assertThat(jdbc.queryForList("select * from " + table).toString()).doesNotContain("private.buyer", "event-with-sensitive-text", "http://localhost");
      assertThat(other).isNotBlank();
    }
    try (var app = start("notifications")) {
      var notifications = app.context().getBean(BuyerNotifications.class);
      int before = sent.size();
      notifications.notify(id, NotificationType.DEALER_REPLY, "event-with-sensitive-text-12345");
      assertThat(sent).hasSize(before);
      assertThat(notifications.deliveryHistory(id).stream().anyMatch(row -> "DELIVERY_UNKNOWN".equals(row.get("state")))).isTrue();
      assertThat(notifications.preferences(session).stream().filter(p -> p.type() == NotificationType.APPOINTMENT).findFirst().orElseThrow().enabled()).isFalse();
      app.client().post().uri("/api/v1/account/deletion").header("Cookie", AccountSessionTestSupport.cookie(session)).header("Account-Request", "1").retrieve().toBodilessEntity();
      notifications.notify(id, NotificationType.ACCOUNT_SECURITY, "after-deletion");
      assertThat(sent).hasSize(before);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      for (String table : java.util.List.of("buyer_notification_preferences", "buyer_notification_deliveries"))
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where account_id = ?", Long.class, id)).isZero();
      assertThat(request(app.client(), "GET", session, null).status()).isEqualTo(401);
    }
    assertThat(output.getAll()).doesNotContain("private.buyer@example.com", "provider payload", "event-with-sensitive-text-12345");
  }

  private HttpAcceptanceHarness.RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--account.notifications.hourly-limit=4",
        "--case.deletion.delay=PT1H");
  }

  private String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email));
    return AccountSessionTestSupport.verify(client, sent.getLast().link().split("#sign-in=")[1]);
  }

  record Result(int status, JsonNode body) {}
  private Result request(RestClient client, String method, String session, Object body) {
    var request = client.method(HttpMethod.valueOf(method)).uri("/api/v1/account/notifications");
    if (session != null) {
      request.header("Cookie", AccountSessionTestSupport.cookie(session));
      if (!"GET".equals(method)) request.header("Account-Request", "1");
    }
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, res) -> new Result(res.getStatusCode().value(), new ObjectMapper().readTree(res.getBody())));
  }
}
