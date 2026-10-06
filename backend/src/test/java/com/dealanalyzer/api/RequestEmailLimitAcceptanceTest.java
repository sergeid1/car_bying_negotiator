package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.recovery.RecoveryEmail;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class RequestEmailLimitAcceptanceTest {
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final List<String> sent = new CopyOnWriteArrayList<>();
  @TempDir Path directory;
  @BeforeEach void clear() { sent.clear(); }

  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail requestLimitTestEmail() {
      return (recipient, link) -> sent.add(recipient);
    }
  }

  @Test void concurrentRequestsFromOnePeerCannotRotateAddressesOrForwardingHeadersToEscapeTheDefault() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("peer", false, EmailConfiguration.class, KEY)) {
      var gate = new CountDownLatch(1);
      var results = new ArrayList<Reply>();
      try (var executor = Executors.newFixedThreadPool(12)) {
        var attempts = new ArrayList<java.util.concurrent.Future<Reply>>();
        for (int i = 0; i < 12; i++) {
          int n = i;
          attempts.add(executor.submit(() -> {
            gate.await();
            return send(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "private-" + n + "@example.com"),
                null, "192.0.2." + n);
          }));
        }
        gate.countDown();
        for (var attempt : attempts) results.add(attempt.get());
      }
      assertThat(results.stream().filter(r -> r.status == 202)).hasSize(5);
      assertThat(results.stream().filter(r -> r.status == 429)).hasSize(7);
      assertThat(results.stream().filter(r -> r.status == 202).map(Reply::body).distinct()).hasSize(1);
      for (var result : results.stream().filter(r -> r.status == 429).toList()) {
        assertThat(Long.parseLong(result.retryAfter)).isBetween(1L, 900L);
        assertThat(result.body).doesNotContain("private-", "192.0.2", "@");
      }
      assertThat(sent).hasSize(5);
      app.client().get().uri("/api/v1/cases/missing").exchange((request, response) -> {
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        return null;
      });
      // Encoded characters and matrix parameters route to the same endpoint and share its bucket.
      for (String path : List.of("/api/v1/account/sign-in-requests;ignored=1", "/api/v1/account/sign-in-%72equests"))
        assertThat(send(app.client(), path, Map.of("email", "escape@example.com"), null, "198.51.100.1").status).as(path).isEqualTo(429);
      assertThat(sent).hasSize(5);
    }
  }

  @Test void signInAndBothRecoveryRoutesShareThePeerBudget() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("shared-peer", false, EmailConfiguration.class, KEY,
        "--security.request-email.peer-requests=3")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      assertThat(send(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "account@example.com"), null, "one").status).isEqualTo(202);
      assertThat(send(app.client(), "/api/v1/cases/" + id + "/recovery-email", Map.of("email", "recover@example.com"), token, "two").status).isEqualTo(200);
      assertThat(send(app.client(), "/api/v1/recovery/requests", Map.of("caseId", id, "email", "recover@example.com"), null, "three").status).isEqualTo(200);
      for (String path : List.of("/api/v1/account/sign-in-requests", "/api/v1/cases/" + id + "/recovery-email", "/api/v1/recovery/requests")) {
        var limited = send(app.client(), path, Map.of("caseId", id, "email", "stranger@example.com"), token, "four");
        assertThat(limited.status).isEqualTo(429);
        assertThat(Long.parseLong(limited.retryAfter)).isPositive();
      }
      assertThat(sent).containsExactly("account@example.com", "recover@example.com", "recover@example.com");
    }
  }

  @Test void globalCeilingSuppressesMailAndLinksWithIdenticalAcknowledgementsAndOneSafeAlert(CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("global", false, EmailConfiguration.class, KEY,
        "--security.request-email.peer-requests=100", "--security.request-email.hourly-limit=2")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      var recovery = send(app.client(), "/api/v1/cases/" + id + "/recovery-email", Map.of("email", "recovery-private@example.com"), token, "one");
      var signIn = send(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "known-private@example.com"), null, "two");
      for (String address : List.of("known-private@example.com", "new-private@example.com")) {
        var suppressed = send(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", address), null, "three");
        assertThat(suppressed.status).isEqualTo(signIn.status);
        assertThat(suppressed.body).isEqualTo(signIn.body);
      }
      var suppressedEnrollment = send(app.client(), "/api/v1/cases/" + id + "/recovery-email", Map.of("email", "recovery-private@example.com"), token, "four");
      var suppressedRecovery = send(app.client(), "/api/v1/recovery/requests", Map.of("caseId", id, "email", "recovery-private@example.com"), null, "five");
      var unknownRecovery = send(app.client(), "/api/v1/recovery/requests", Map.of("caseId", id, "email", "unknown-private@example.com"), null, "six");
      for (var suppressed : List.of(suppressedEnrollment, suppressedRecovery, unknownRecovery)) {
        assertThat(suppressed.status).isEqualTo(recovery.status);
        assertThat(suppressed.body).isEqualTo(recovery.body);
      }
      assertThat(sent).containsExactly("recovery-private@example.com", "known-private@example.com");
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_sign_in_links", Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from case_recovery_tokens", Long.class)).isEqualTo(1);
      assertThat(output.getAll().split("REQUEST_EMAIL_HOURLY_LIMIT_REACHED", -1)).hasSize(2);
      assertThat(output.getAll()).doesNotContain("private@example.com");
      // Even an account created while mail is suppressed is still abandoned and purged normally.
      jdbc.update("update buyer_accounts set created_at = dateadd('HOUR', -1, current_timestamp)");
      jdbc.update("update buyer_account_sign_in_links set expires_at = dateadd('HOUR', -1, current_timestamp)");
      app.context().getBean(com.dealanalyzer.account.AccountDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from buyer_accounts", Long.class)).isZero();
    }
  }

  @Test void concurrentDeliveriesCannotExceedTheGlobalCeiling() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("global-race", false, EmailConfiguration.class, KEY,
        "--security.request-email.peer-requests=100", "--security.request-email.hourly-limit=3")) {
      try (var executor = Executors.newFixedThreadPool(8)) {
        var gate = new CountDownLatch(1);
        var attempts = new ArrayList<java.util.concurrent.Future<Reply>>();
        for (int i = 0; i < 8; i++) {
          int n = i;
          attempts.add(executor.submit(() -> { gate.await();
            return send(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "concurrent-" + n + "@example.com"), null, "peer-" + n); }));
        }
        gate.countDown();
        var replies = new ArrayList<Reply>();
        for (var attempt : attempts) replies.add(attempt.get());
        assertThat(replies).allMatch(r -> r.status == 202);
        assertThat(replies.stream().map(Reply::body).distinct()).hasSize(1);
      }
      assertThat(sent).hasSize(3);
    }
  }

  @Test void nonpositiveGlobalCeilingRefusesStartup() {
    for (String value : List.of("0", "-1"))
      assertThatThrownBy(() -> new HttpAcceptanceHarness(directory).start("invalid-" + value, false,
          "--security.request-email.hourly-limit=" + value))
          .hasRootCauseMessage("security.request-email.hourly-limit must be positive");
  }

  @Test void replaysAndIneligibleRequestsDoNotConsumeDeliveryCapacity(CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("replay-capacity", false, EmailConfiguration.class, KEY,
        "--security.request-email.peer-requests=100", "--security.request-email.hourly-limit=2")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + id + "/recovery-email";
      post(app.client(), path, Map.of("email", "recover@example.com"), "same-recovery", token);
      post(app.client(), path, Map.of("email", "recover@example.com"), "same-recovery", token);
      post(app.client(), "/api/v1/recovery/requests", Map.of("caseId", id, "email", "unknown@example.com"));
      post(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "same-sign-in");
      post(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "same-sign-in");
      assertThat(sent).containsExactly("recover@example.com", "buyer@example.com");
      assertThat(output.getAll()).doesNotContain("REQUEST_EMAIL_HOURLY_LIMIT_REACHED");
      post(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "another@example.com"), "another");
      assertThat(sent).hasSize(2);
      assertThat(output.getAll()).contains("REQUEST_EMAIL_HOURLY_LIMIT_REACHED");
    }
  }

  private record Reply(int status, String body, String retryAfter) {}
  private static Reply send(RestClient client, String path, Map<String, String> body, String token, String forwarded) {
    // Supply an already encoded URI so percent escapes reach the server unchanged.
    var request = client.post().uri(builder -> java.net.URI.create(builder.build().toString() + path)).contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Forwarded-For", forwarded)
        .header("Forwarded", "for=" + forwarded).body(body);
    if (token != null) request.header("Case-Access-Token", token);
    return request.exchange((r, response) -> new Reply(response.getStatusCode().value(),
        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8), response.getHeaders().getFirst("Retry-After")));
  }
}
