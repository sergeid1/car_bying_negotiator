package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.post;
import com.dealanalyzer.account.AccountDeletionWorker;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class PasskeyAcceptanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String ROOT = "/api/v1/account/passkeys", ORIGIN = "http://localhost:5173";
  @TempDir Path directory;

  @Test void registrationFailuresConsumeAttemptsAndCounterlessAuthenticatorsCanSignIn() throws Exception {
    BuyerAccountAcceptanceTest.sent.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("counterless", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client(); var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      String session = emailSignIn(client, "buyer@example.com");
      var authenticator = new SoftwarePasskey();
      var wrongOrigin = options(client, "/registration/options", session);
      assertThat(finishRegistration(client, session, wrongOrigin, authenticator.registration(wrongOrigin, "http://localhost:5174")).status).isEqualTo(400);
      assertThat(finishRegistration(client, session, wrongOrigin, authenticator.registration(wrongOrigin, ORIGIN)).status).isEqualTo(400);
      var expired = options(client, "/registration/options", session);
      jdbc.update("update buyer_account_passkey_challenges set expires_at = ? where id = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), expired.path("attemptId").asText());
      assertThat(finishRegistration(client, session, expired, authenticator.registration(expired, ORIGIN)).status).isEqualTo(400);
      var register = options(client, "/registration/options", session);
      assertThat(finishRegistration(client, session, register, authenticator.registration(register, ORIGIN)).status).isEqualTo(200);
      // Zero throughout is valid. Last-used and backup state still update on each signed assertion.
      for (int i = 0; i < 2; i++) {
        var signIn = options(client, "/sign-in/options", null);
        assertThat(finish(client, signIn, authenticator.assertion(signIn, ORIGIN, 0, authenticator.userHandle, 0x0d)).status).isEqualTo(200);
      }
      assertThat(jdbc.queryForObject("select backup_state from buyer_account_passkeys", Boolean.class)).isFalse();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_security_events", Long.class)).isZero();
      var missingBackupEligibility = options(client, "/sign-in/options", null);
      assertThat(finish(client, missingBackupEligibility, authenticator.assertion(missingBackupEligibility, ORIGIN, 0, authenticator.userHandle, 0x05)).status).isEqualTo(400);
      var tampered = options(client, "/sign-in/options", null);
      var assertion = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(authenticator.assertion(tampered, ORIGIN, 0));
      ((com.fasterxml.jackson.databind.node.ObjectNode) assertion.get("response")).put("signature", SoftwarePasskey.b64(new byte[64]));
      assertThat(finish(client, tampered, assertion.toString()).status).isEqualTo(400);
      var concurrent = options(client, "/sign-in/options", null);
      String response = authenticator.assertion(concurrent, ORIGIN, 0);
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var start = new java.util.concurrent.CountDownLatch(1);
        var one = executor.submit(() -> { start.await(); return finish(client, concurrent, response).status; });
        var two = executor.submit(() -> { start.await(); return finish(client, concurrent, response).status; });
        start.countDown(); assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 400);
      }
      var revoked = options(client, "/registration/options", session);
      assertThat(request(client, "POST", "/api/v1/account/sign-out", Map.of(), session).status).isEqualTo(204);
      assertThat(finishRegistration(client, session, revoked, new SoftwarePasskey().registration(revoked, ORIGIN)).status).isEqualTo(401);
    }
  }

  @Test void signedPasskeysRespectAttemptsOwnershipLifecycleAndSessionRevocation() throws Exception {
    BuyerAccountAcceptanceTest.sent.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("passkeys", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--security.request-email.peer-requests=100")) {
      var client = app.client(); var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      String session = emailSignIn(client, "buyer@example.com"), other = emailSignIn(client, "other@example.com");
      assertThat(request(client, "POST", ROOT + "/registration/options", Map.of(), null).status).isEqualTo(401);
      var authenticator = new SoftwarePasskey();
      var register = options(client, "/registration/options", session);
      String registration = authenticator.registration(register, ORIGIN);
      assertThat(finishRegistration(client, other, register, registration).status).isEqualTo(400);
      assertThat(finishRegistration(client, emailSignIn(client, "buyer@example.com"), register, registration).status).isEqualTo(400);
      var added = finishRegistration(client, session, register, registration);
      assertThat(added.status).isEqualTo(200);
      String id = added.body.at("/0/id").asText();
      assertThat(added.body.at("/0/nickname").asText()).isEqualTo("My device");
      assertThat(finishRegistration(client, session, register, registration).status).isEqualTo(400);
      assertThat(BuyerAccountAcceptanceTest.sent.stream().filter(mail -> mail.purpose() == com.dealanalyzer.recovery.RecoveryEmail.Purpose.ACCOUNT_SECURITY).count()).isEqualTo(1);
      assertThat(jdbc.queryForObject("select backup_eligible from buyer_account_passkeys where id = ?", Boolean.class, id)).isTrue();
      assertThat(jdbc.queryForObject("select backup_state from buyer_account_passkeys where id = ?", Boolean.class, id)).isTrue();

      var signIn = options(client, "/sign-in/options", null);
      assertThat(signIn.path("publicKey").has("allowCredentials")).isFalse();
      String assertion = authenticator.assertion(signIn, ORIGIN, 1);
      var signed = finish(client, signIn, assertion);
      assertThat(signed.status).isEqualTo(200);
      assertThat(signed.cookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Max-Age=1209600");
      assertThat(signed.body.has("sessionToken")).isFalse();
      String passkeySession = signed.cookie.substring("account_session=".length(), signed.cookie.indexOf(';'));
      assertThat(request(client, "GET", "/api/v1/account", null, passkeySession).body.at("/contact/email").asText()).isEqualTo("buyer@example.com");
      assertThat(request(client, "GET", ROOT, null, session).body.at("/0/lastUsedAt").isNull()).isFalse();
      assertThat(finish(client, signIn, assertion).status).isEqualTo(400);
      var unrelatedAttempt = options(client, "/sign-in/options", null);
      assertThat(finish(client, unrelatedAttempt, assertion).status).isEqualTo(400);
      var wrongOrigin = options(client, "/sign-in/options", null);
      assertThat(finish(client, wrongOrigin, authenticator.assertion(wrongOrigin, "https://phishing.example", 2)).status).isEqualTo(400);
      // Failed verification consumes the attempt too.
      assertThat(finish(client, wrongOrigin, authenticator.assertion(wrongOrigin, ORIGIN, 2)).status).isEqualTo(400);
      var crossAccount = options(client, "/sign-in/options", null);
      String otherId = jdbc.queryForObject("select id from buyer_accounts where id <> ?", String.class,
          new String(Base64.getUrlDecoder().decode(authenticator.userHandle), java.nio.charset.StandardCharsets.UTF_8));
      assertThat(finish(client, crossAccount, authenticator.assertion(crossAccount, ORIGIN, 2,
          SoftwarePasskey.b64(otherId.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0x1d)).status).isEqualTo(400);
      var noVerification = options(client, "/sign-in/options", null);
      assertThat(finish(client, noVerification, authenticator.assertion(noVerification, ORIGIN, 2, authenticator.userHandle, 0x19)).status).isEqualTo(400);
      var expired = options(client, "/sign-in/options", null);
      jdbc.update("update buyer_account_passkey_challenges set expires_at = ? where id = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), expired.path("attemptId").asText());
      assertThat(finish(client, expired, authenticator.assertion(expired, ORIGIN, 2)).status).isEqualTo(400);
      for (long count : new long[] {1, 0}) {
        var regressed = options(client, "/sign-in/options", null);
        assertThat(finish(client, regressed, authenticator.assertion(regressed, ORIGIN, count)).status).isEqualTo(400);
      }
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_security_events where event_type = 'PASSKEY_COUNTER_REGRESSION'", Long.class)).isEqualTo(2);
      assertThat(jdbc.queryForObject("select signature_count from buyer_account_passkeys where id = ?", Long.class, id)).isEqualTo(1);
      // Metadata only: no credential ID, public key or attempt data in the ZIP.
      byte[] zip = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session)).header("Account-Request", "1").retrieve().body(byte[].class);
      String exported = "";
      try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
        for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
          if (entry.getName().equals("account.json")) exported = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      assertThat(exported).contains("My device", "PASSKEY_COUNTER_REGRESSION").doesNotContain(SoftwarePasskey.b64(authenticator.id), "public_key_cose", "session_hash", "options_json");
      assertThat(request(client, "POST", "/api/v1/account/sign-out?everywhere=true", Map.of(), passkeySession).status).isEqualTo(204);
      assertThat(request(client, "GET", "/api/v1/account", null, session).status).isEqualTo(401);
      assertThat(request(client, "GET", "/api/v1/account", null, passkeySession).status).isEqualTo(401);
      session = emailSignIn(client, "buyer@example.com");
      assertThat(request(client, "DELETE", ROOT + "/" + id, null, other).status).isEqualTo(404);
      var beforeRemoval = options(client, "/sign-in/options", null);
      assertThat(request(client, "DELETE", ROOT + "/" + id, null, session).status).isEqualTo(200);
      assertThat(finish(client, beforeRemoval, authenticator.assertion(beforeRemoval, ORIGIN, 2)).status).isEqualTo(400);
      var replacement = new SoftwarePasskey(); var replacementOptions = options(client, "/registration/options", session);
      assertThat(finishRegistration(client, session, replacementOptions, replacement.registration(replacementOptions, ORIGIN)).status).isEqualTo(200);
      var beforeDeletion = options(client, "/sign-in/options", null);
      assertThat(request(client, "POST", "/api/v1/account/deletion", Map.of(), session).status).isEqualTo(202);
      assertThat(finish(client, beforeDeletion, replacement.assertion(beforeDeletion, ORIGIN, 1)).status).isEqualTo(400);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      for (String table : List.of("buyer_account_passkeys", "buyer_account_security_events"))
        assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_passkey_challenges where account_id is not null", Long.class)).isZero();
      var afterDeletion = options(client, "/sign-in/options", null);
      assertThat(finish(client, afterDeletion, replacement.assertion(afterDeletion, ORIGIN, 2)).status).isEqualTo(400);
    }
  }
  static String emailSignIn(RestClient client, String address) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", address));
    return AccountSessionTestSupport.verify(client, BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1]);
  }
  static JsonNode options(RestClient client, String path, String session) {
    var result = request(client, "POST", ROOT + path, Map.of(), session); assertThat(result.status).isEqualTo(200); return result.body;
  }
  static Result finishRegistration(RestClient client, String session, JsonNode options, String credential) {
    return request(client, "POST", ROOT + "/registration", Map.of("attemptId", options.path("attemptId").asText(), "nickname", "My device", "credential", credential), session);
  }
  static Result finish(RestClient client, JsonNode options, String credential) {
    return request(client, "POST", ROOT + "/sign-in", Map.of("attemptId", options.path("attemptId").asText(), "credential", credential), null);
  }
  record Result(int status, JsonNode body, String cookie) {}
  static Result request(RestClient client, String method, String path, Object body, String session) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Account-Request", "1");
    if (session != null) request.header("Cookie", AccountSessionTestSupport.cookie(session));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes), response.getHeaders().getFirst("Set-Cookie"));
    });
  }
}
