package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.api.PasskeyAcceptanceTest.*;
import com.dealanalyzer.account.AccountDeletionWorker;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
class PasswordAcceptanceTest {
  @TempDir Path directory;
  private static final String PASSWORD = "a sufficiently long phrase", NEXT = "another long private phrase";
  private static final String PATH = "/api/v1/account/password";
  // Adapt the shared HTTP helper's record into a fixture with readable fields.
  private static class Result {
    final int status; final com.fasterxml.jackson.databind.JsonNode body; final String cookie;
    Result(PasskeyAcceptanceTest.Result result) { status = result.status(); body = result.body(); cookie = result.cookie(); }
  }
  private static Result request(org.springframework.web.client.RestClient client, String method, String path, Object body, String session) {
    return new Result(PasskeyAcceptanceTest.request(client, method, path, body, session));
  }

  @Test void optionalPasswordLifecycleRecoveryStepUpExportAndDeletion(CapturedOutput output) throws Exception {
    BuyerAccountAcceptanceTest.sent.clear();
    var prefixes = new ArrayList<String>();
    var outage = new AtomicBoolean();
    String digest = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1")
        .digest("known breached phrase".getBytes(StandardCharsets.UTF_8)));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/range/", exchange -> {
      prefixes.add(exchange.getRequestURI().getPath().substring(7));
      assertThat(exchange.getRequestHeaders().getFirst("Add-Padding")).isEqualTo("true");
      assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).isEqualTo("Deal-X-Ray-password-check");
      byte[] response = (digest.substring(5) + ":42\r\n" + "0".repeat(35) + ":0\r\n").getBytes(StandardCharsets.US_ASCII);
      exchange.sendResponseHeaders(outage.get() ? 503 : 200, response.length);
      exchange.getResponseBody().write(response); exchange.close();
    });
    server.start();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("password", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--security.request-email.peer-requests=100", "--account.password.breach-check.url=http://127.0.0.1:" + server.getAddress().getPort() + "/range/")) {
      var client = app.client(); var jdbc = app.context().getBean(JdbcTemplate.class);
      String original = emailSignIn(client, "buyer@example.com");
      String second = emailSignIn(client, "buyer@example.com");
      assertThat(request(client, "GET", PATH, null, original).body.path("configured").asBoolean()).isFalse();
      assertThat(request(client, "PUT", PATH, Map.of("password", "short"), original).status).isEqualTo(400);
      assertThat(request(client, "PUT", PATH, Map.of("password", "界".repeat(25)), original).status).isEqualTo(400);
      assertThat(request(client, "PUT", PATH, Map.of("password", "known breached phrase"), original).body.path("code").asText()).isEqualTo("PASSWORD_BREACHED");
      assertThat(request(client, "PUT", PATH, Map.of("password", PASSWORD), original).status).isEqualTo(200);
      assertThat(request(client, "GET", "/api/v1/account", null, second).status).isEqualTo(401);
      String hash = jdbc.queryForObject("select password_hash from buyer_accounts", String.class);
      assertThat(hash).startsWith("$2a$12$").doesNotContain(PASSWORD);
      assertThat(prefixes).allMatch(value -> value.matches("[A-F0-9]{5}"));
      assertThat(prefixes.getFirst()).isEqualTo(digest.substring(0, 5));
      assertThat(BuyerAccountAcceptanceTest.sent).anyMatch(mail -> mail.purpose() == com.dealanalyzer.recovery.RecoveryEmail.Purpose.ACCOUNT_SECURITY);
      var unknown = request(client, "POST", PATH + "/sign-in", Map.of("email", "unknown@example.com", "password", PASSWORD), null);
      String noPassword = emailSignIn(client, "no-password@example.com");
      var absent = request(client, "POST", PATH + "/sign-in", Map.of("email", "no-password@example.com", "password", PASSWORD), null);
      var wrong = request(client, "POST", PATH + "/sign-in", Map.of("email", "buyer@example.com", "password", NEXT), null);
      assertThat(unknown.status).isEqualTo(401); assertThat(absent.status).isEqualTo(401); assertThat(wrong.status).isEqualTo(401);
      assertThat(unknown.body.path("detail")).isEqualTo(absent.body.path("detail")).isEqualTo(wrong.body.path("detail"));
      var signed = request(client, "POST", PATH + "/sign-in", Map.of("email", "BUYER@example.com", "password", PASSWORD), null);
      assertThat(signed.status).isEqualTo(200);
      assertThat(signed.cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1", "Max-Age=");
      String passwordSession = signed.cookie.split(";", 2)[0].split("=", 2)[1];
      assertThat(jdbc.queryForObject("select sign_in_method from buyer_account_sessions where token_hash = (select token_hash from buyer_account_sessions where sign_in_method = 'PASSWORD')", String.class)).isEqualTo("PASSWORD");
      for (String method : List.of("POST", "DELETE")) {
        String mailboxPath = method.equals("POST") ? "/authorizations" : "/connection";
        assertThat(request(client, method, "/api/v1/account/mailbox" + mailboxPath,
            method.equals("POST") ? Map.of("provider", "GOOGLE") : null, passwordSession).body.path("code").asText()).isEqualTo("MAILBOX_STEP_UP");
      }
      assertThat(request(client, "POST", "/api/v1/account/mailbox/connection", Map.of("state", "x", "code", "x"), passwordSession).status).isEqualTo(403);
      assertThat(request(client, "POST", ROOT + "/registration/options", Map.of(), passwordSession).status).isEqualTo(403);
      // A real passkey sign-in records its method and admits the mailbox step-up boundary.
      var device = new SoftwarePasskey(); var options = options(client, "/registration/options", original);
      assertThat(finishRegistration(client, original, options, device.registration(options, ORIGIN)).status()).isEqualTo(200);
      var assertion = options(client, "/sign-in/options", null);
      var passkey = finish(client, assertion, device.assertion(assertion, ORIGIN, 1));
      String passkeySession = passkey.cookie().split(";", 2)[0].split("=", 2)[1];
      app.context().getBean(com.dealanalyzer.account.BuyerAccountService.class).requireMailboxStepUp(passkeySession);
      assertThat(request(client, "PUT", PATH, Map.of("password", NEXT), original).status).isEqualTo(200);
      assertThat(request(client, "GET", "/api/v1/account", null, passwordSession).status).isEqualTo(401);
      assertThat(request(client, "GET", "/api/v1/account", null, passkeySession).status).isEqualTo(401);
      assertThat(request(client, "GET", "/api/v1/account", null, original).status).isEqualTo(200);
      // Forgot password uses exactly the email-link sign-in flow, with no extra reset token.
      String reset = emailSignIn(client, "buyer@example.com");
      outage.set(true);
      assertThat(request(client, "PUT", PATH, Map.of("password", PASSWORD), reset).status).isEqualTo(200);
      assertThat(request(client, "GET", "/api/v1/account", null, original).status).isEqualTo(401);
      byte[] export = client.post().uri("/api/v1/account/export").header("Account-Request", "1")
          .header("Cookie", AccountSessionTestSupport.cookie(reset)).retrieve().body(byte[].class);
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(export))) {
        while (zip.getNextEntry() != null) assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
            .doesNotContain(hash, "passwordHash", "password_hash", PASSWORD);
      }
      jdbc.update("update buyer_account_sessions set created_at = ? where revoked_at is null and account_id = (select id from buyer_accounts where password_hash is not null)", Timestamp.from(Instant.now().minusSeconds(601)));
      assertThat(request(client, "PUT", PATH, Map.of("password", NEXT), reset).status).isEqualTo(403);
      assertThat(request(client, "DELETE", PATH, null, reset).status).isEqualTo(403);
      String recent = emailSignIn(client, "buyer@example.com");
      assertThat(request(client, "DELETE", PATH, null, recent).body.path("configured").asBoolean()).isFalse();
      assertThat(request(client, "POST", PATH + "/sign-in", Map.of("email", "buyer@example.com", "password", PASSWORD), null).status).isEqualTo(401);
      assertThat(request(client, "PUT", PATH, Map.of("password", NEXT), recent).status).isEqualTo(200);
      assertThat(request(client, "POST", "/api/v1/account/deletion", null, recent).status).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from buyer_accounts where password_hash is not null", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_security_events", Long.class)).isZero();
      assertThat(request(client, "GET", PATH, null, recent).status).isEqualTo(401);
      assertThat(request(client, "GET", PATH, null, noPassword).status).isEqualTo(200);
      assertThat(output).contains("PASSWORD_BREACH_CHECK_UNAVAILABLE").doesNotContain(PASSWORD, NEXT, hash, digest);
    } finally { server.stop(0); }
  }

  @Test void accountAndClientBackoffDoNotDependOnWhetherAnAccountExists() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("password-limits", false,
        BuyerAccountAcceptanceTest.EmailConfiguration.class, "--account.password.breach-check.enabled=false")) {
      var client = app.client();
      for (int i = 0; i < 5; i++) assertThat(request(client, "POST", PATH + "/sign-in",
          Map.of("email", "unknown@example.com", "password", PASSWORD), null).status).isEqualTo(401);
      assertThat(request(client, "POST", PATH + "/sign-in", Map.of("email", "unknown@example.com", "password", PASSWORD), null).status).isEqualTo(429);
      for (int i = 0; i < 15; i++) assertThat(request(client, "POST", PATH + "/sign-in",
          Map.of("email", "unknown" + i + "@example.com", "password", PASSWORD), null).status).isEqualTo(401);
      assertThat(request(client, "POST", PATH + "/sign-in", Map.of("email", "fresh@example.com", "password", PASSWORD), null).status).isEqualTo(429);
    }
  }
}
