package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
class BuyerAccountAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  private static final String SESSION = "Account-Session-Token";
  static final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();
  record Sent(String recipient, String link, RecoveryEmail.Purpose purpose) {}

  /** Deterministic stand-in for the shared email adapter; it records instead of delivering. */
  @TestConfiguration static class EmailConfiguration {
    @Bean @Primary RecoveryEmail testEmail() {
      return new RecoveryEmail() {
        public void send(String recipient, String link) { send(recipient, link, Purpose.CASE_RECOVERY); }
        @Override public void send(String recipient, String link, Purpose purpose) { sent.add(new Sent(recipient, link, purpose)); }
      };
    }
  }

  @TempDir Path directory;

  @BeforeEach void clear() { sent.clear(); }

  @Test void signInIsPasswordlessSingleUseAndSessionsAreRevocable() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("sign-in", false, EmailConfiguration.class, KEY)) {
      var client = app.client();
      var first = post(client, "/api/v1/account/sign-in-requests", Map.of("email", "Buyer@Example.com"), "request-1");
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "Buyer@Example.com"), "request-1");
      assertThat(sent).hasSize(1);
      assertThat(sent.getFirst().recipient()).isEqualTo("buyer@example.com");
      assertThat(sent.getFirst().purpose()).isEqualTo(RecoveryEmail.Purpose.ACCOUNT_SIGN_IN);
      // A new address gets the same acknowledgement as a known one.
      assertThat(post(client, "/api/v1/account/sign-in-requests", Map.of("email", "other@example.com"), "request-2"))
          .isEqualTo(first);
      String link = sent.getFirst().link();
      assertThat(link).startsWith("http://localhost:5173/#sign-in=");
      String secret = link.split("#sign-in=")[1];
      assertThat(secret.length()).isGreaterThanOrEqualTo(43);

      var session = post(client, "/api/v1/account/sign-in/verify", Map.of("token", secret));
      String token = session.path("sessionToken").asText();
      assertThat(token).isNotBlank();
      var reused = exchange(client, "POST", "/api/v1/account/sign-in/verify", Map.of("token", secret), null);
      assertThat(reused.status()).isEqualTo(410);
      assertThat(reused.body().path("code").asText()).isEqualTo("SIGN_IN_LINK_USED");
      assertThat(reused.body().path("detail").asText()).contains("already used");
      assertThat(exchange(client, "POST", "/api/v1/account/sign-in/verify", Map.of("token", "unknown"), null).status())
          .isEqualTo(404);

      // A page reload only re-reads the account with the stored session.
      var account = account(client, token);
      assertThat(account.at("/contact/email").asText()).isEqualTo("buyer@example.com");
      assertThat(account.path("cases").size()).isZero();

      // A second session on another device; signing out revokes only the current one server-side.
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "request-3");
      String other = post(client, "/api/v1/account/sign-in/verify",
          Map.of("token", sent.getLast().link().split("#sign-in=")[1])).path("sessionToken").asText();
      assertThat(exchange(client, "POST", "/api/v1/account/sign-out", Map.of(), token).status()).isEqualTo(204);
      var ended = exchange(client, "GET", "/api/v1/account", null, token);
      assertThat(ended.status()).isEqualTo(401);
      assertThat(ended.body().path("code").asText()).isEqualTo("SESSION_ENDED");
      assertThat(account(client, other).at("/contact/email").asText()).isEqualTo("buyer@example.com");
      assertThat(exchange(client, "POST", "/api/v1/account/sign-out?everywhere=true", Map.of(), other).status()).isEqualTo(204);
      assertThat(exchange(client, "GET", "/api/v1/account", null, other).status()).isEqualTo(401);
    }
  }

  @Test void expiredLinksAndSessionsAreRefusedWithAClearMessage() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("expired", false, EmailConfiguration.class, KEY,
        "--account.sign-in.link-lifetime=PT0S")) {
      post(app.client(), "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "request");
      var expired = exchange(app.client(), "POST", "/api/v1/account/sign-in/verify",
          Map.of("token", sent.getLast().link().split("#sign-in=")[1]), null);
      assertThat(expired.status()).isEqualTo(410);
      assertThat(expired.body().path("code").asText()).isEqualTo("SIGN_IN_LINK_EXPIRED");
      assertThat(expired.body().path("detail").asText()).isEqualTo("This sign-in link has expired. Request a new link.");
      // The unverified sign-up and its expired link are removed by the worker.
      app.context().getBean(com.dealanalyzer.account.AccountDeletionWorker.class).runOnce();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from buyer_accounts", Long.class)).isZero();
    }
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("expired-session", false, EmailConfiguration.class, KEY,
        "--account.session.lifetime=PT0S")) {
      String token = signIn(app.client(), "buyer@example.com");
      assertThat(exchange(app.client(), "GET", "/api/v1/account", null, token).status()).isEqualTo(401);
    }
  }

  @Test void contactDetailsAreStoredOnceEncryptedAndNeverLogged(CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("contact", false, EmailConfiguration.class, KEY)) {
      String token = signIn(app.client(), "private.buyer@example.com");
      var updated = exchange(app.client(), "PUT", "/api/v1/account/contact",
          Map.of("firstName", "Avery", "lastName", "Quintana", "phone", "+1 919 555 0142"), token);
      assertThat(updated.status()).isEqualTo(200);
      assertThat(updated.body().path("contact").path("firstName").asText()).isEqualTo("Avery");
      assertThat(exchange(app.client(), "PUT", "/api/v1/account/contact", Map.of("phone", "call me"), token).status())
          .isEqualTo(400);
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from buyer_accounts", Long.class)).isEqualTo(1);
      // No table holds any contact detail in clear text.
      for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'", String.class)) {
        String rows = jdbc.queryForList("select * from " + table).toString().toLowerCase();
        assertThat(rows).as(table).doesNotContain("private.buyer", "avery", "quintana", "555 0142");
      }
      assertThat(output.getAll().toLowerCase()).doesNotContain("private.buyer", "avery", "quintana", "555 0142", token.toLowerCase());
      // The Buyer's account survives a restart and decrypts with the configured key.
    }
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("contact", false, EmailConfiguration.class,
        "--document.storage.encryption-key=a-different-key-with-at-least-32-bytes")) {
      // A different key cannot read the ciphertext; the Buyer gets a clear, safe failure, not contact data.
      String token = signIn(app.client(), "private.buyer@example.com");
      var failed = exchange(app.client(), "GET", "/api/v1/account", null, token);
      assertThat(failed.status()).isEqualTo(503);
      assertThat(failed.body().toString()).doesNotContain("private.buyer");
    }
  }

  @Test void aCaseOpenedByItsLinkCanBeAttachedAndItsLinkKeepsWorking() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("attach", false, EmailConfiguration.class, KEY)) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String caseId = created.at("/case/id").asText(), caseToken = created.path("accessToken").asText();
      String token = signIn(client, "buyer@example.com");
      assertThat(attach(client, token, caseId, "wrong").status()).isEqualTo(404);
      var attached = attach(client, token, caseId, caseToken);
      assertThat(attached.status()).isEqualTo(200);
      assertThat(attached.body().at("/cases/0/caseId").asText()).isEqualTo(caseId);
      assertThat(attached.body().at("/cases/0/state").asText()).isEqualTo("ACTIVE");
      assertThat(attach(client, token, caseId, caseToken).body().path("cases").size()).isEqualTo(1);
      assertThat(get(client, "/api/v1/cases/" + caseId, caseToken).path("status").asText()).isEqualTo("DRAFT");
      String someoneElse = signIn(client, "other@example.com");
      var conflict = attach(client, someoneElse, caseId, caseToken);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.body().path("code").asText()).isEqualTo("CASE_ATTACHED_ELSEWHERE");
      assertThat(attach(client, null, caseId, caseToken).status()).isEqualTo(401);
    }
  }

  @Test void exportIncludesEveryAttachedCaseExport() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("export", false, EmailConfiguration.class, KEY)) {
      var client = app.client();
      String token = signIn(client, "buyer@example.com");
      exchange(client, "PUT", "/api/v1/account/contact", Map.of("firstName", "Avery"), token);
      var ids = new java.util.ArrayList<String>();
      for (int i = 0; i < 2; i++) {
        var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
        ids.add(created.at("/case/id").asText());
        attach(client, token, ids.getLast(), created.path("accessToken").asText());
      }
      byte[] zip = client.post().uri("/api/v1/account/export").header(SESSION, token).retrieve().body(byte[].class);
      var entries = unzip(zip);
      assertThat(entries).containsKeys("README.txt", "account.json",
          "cases/buying-case-" + ids.get(0) + ".zip", "cases/buying-case-" + ids.get(1) + ".zip");
      var account = JSON.readTree(entries.get("account.json"));
      assertThat(account.at("/contact/email").asText()).isEqualTo("buyer@example.com");
      assertThat(account.at("/contact/firstName").asText()).isEqualTo("Avery");
      assertThat(account.at("/cases/0/export").asText()).isEqualTo("INCLUDED");
      String text = new String(entries.get("account.json"), StandardCharsets.UTF_8);
      assertThat(text).doesNotContain(token, "token_hash", "tokenHash");
      var caseEntries = unzip(entries.get("cases/buying-case-" + ids.get(0) + ".zip"));
      assertThat(new String(caseEntries.get("case-data.json"), StandardCharsets.UTF_8)).contains(ids.get(0), "consent_records");
      assertThat(exchange(client, "POST", "/api/v1/account/export", Map.of(), "wrong").status()).isEqualTo(401);
    }
  }

  @Test void deletionRevokesAccessAndRemovesTheAccountAndEveryAttachedCase() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("deletion", false, EmailConfiguration.class, KEY,
        "--case.deletion.delay=PT1H")) {
      var client = app.client();
      String token = signIn(client, "buyer@example.com");
      String other = signIn(client, "buyer@example.com");
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String caseId = created.at("/case/id").asText(), caseToken = created.path("accessToken").asText();
      attach(client, token, caseId, caseToken);
      var unattached = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());

      var receipt = exchange(client, "POST", "/api/v1/account/deletion", Map.of(), token);
      assertThat(receipt.status()).isEqualTo(202);
      assertThat(receipt.body().path("state").asText()).isEqualTo("DELETION_PENDING");
      assertThat(receipt.body().at("/cases/0/caseId").asText()).isEqualTo(caseId);
      assertThat(receipt.body().at("/cases/0/state").asText()).isEqualTo("DELETION_PENDING");
      // Access ends at once: every session, sign-in and the attached case's link.
      assertThat(exchange(client, "GET", "/api/v1/account", null, token).status()).isEqualTo(401);
      assertThat(exchange(client, "GET", "/api/v1/account", null, other).status()).isEqualTo(401);
      assertThat(exchange(client, "GET", "/api/v1/cases/" + caseId, null, null, caseToken).status()).isEqualTo(404);
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "buyer@example.com"), "during-deletion");
      assertThat(sent).hasSize(2);
      assertThat(exchange(client, "POST", "/api/v1/account/deletion", Map.of(), token).body().path("id"))
          .isEqualTo(receipt.body().path("id"));

      var accountWorker = app.context().getBean(com.dealanalyzer.account.AccountDeletionWorker.class);
      accountWorker.runOnce();
      var waiting = exchange(client, "GET", "/api/v1/account/deletion", null, token).body();
      assertThat(waiting.path("state").asText()).isEqualTo("DELETION_PENDING");
      assertThat(waiting.path("accountRemovedAt").isNull()).isFalse();
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      accountWorker.runOnce();
      var completed = exchange(client, "GET", "/api/v1/account/deletion", null, token).body();
      assertThat(completed.path("state").asText()).isEqualTo("DELETED");
      assertThat(completed.path("verification").asText()).isEqualTo("ACCOUNT_GRAPH_AND_CASES_ABSENT_V1");
      assertThat(completed.path("backupStatus").asText()).isEqualTo("UNKNOWN");
      assertThat(completed.at("/cases/0/state").asText()).isEqualTo("DELETED");
      assertThat(exchange(client, "GET", "/api/v1/account/deletion", null, "wrong").status()).isEqualTo(404);

      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      for (String table : List.of("buyer_accounts", "buyer_account_sessions", "buyer_account_sign_in_links", "buyer_account_cases"))
        assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
      assertThat(jdbc.queryForObject("select count(*) from analysis_cases where id = ?", Long.class, caseId)).isZero();
      // A case that was never attached is untouched, and the address can start a new account.
      assertThat(get(client, "/api/v1/cases/" + unattached.at("/case/id").asText(), unattached.path("accessToken").asText())
          .path("status").asText()).isEqualTo("DRAFT");
      String fresh = signIn(client, "buyer@example.com");
      assertThat(account(client, fresh).path("cases").size()).isZero();
    }
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), java.util.UUID.randomUUID().toString());
    String secret = sent.getLast().link().split("#sign-in=")[1];
    return post(client, "/api/v1/account/sign-in/verify", Map.of("token", secret)).path("sessionToken").asText();
  }

  private static JsonNode account(RestClient client, String token) {
    var result = exchange(client, "GET", "/api/v1/account", null, token);
    assertThat(result.status()).isEqualTo(200);
    return result.body();
  }

  private static Result attach(RestClient client, String token, String caseId, String caseToken) {
    return exchange(client, "POST", "/api/v1/account/cases", Map.of("caseId", caseId), token, caseToken);
  }

  record Result(int status, JsonNode body) {}

  private static Result exchange(RestClient client, String method, String path, Object body, String session) {
    return exchange(client, method, path, body, session, null);
  }

  private static Result exchange(RestClient client, String method, String path, Object body, String session, String caseToken) {
    var request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
    if (session != null) request.header(SESSION, session);
    if (caseToken != null) request.header("Case-Access-Token", caseToken);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    var entries = new HashMap<String, byte[]>();
    try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) entries.put(entry.getName(), input.readAllBytes());
    }
    return entries;
  }
}
