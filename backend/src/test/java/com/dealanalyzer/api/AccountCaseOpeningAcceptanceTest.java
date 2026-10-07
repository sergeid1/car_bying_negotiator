package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Ticket 88: a signed-in Buyer opens and saves Buying Cases through the account; case links keep working. */
class AccountCaseOpeningAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "--document.storage.encryption-key=0123456789abcdef0123456789abcdef";
  @TempDir Path directory;

  @BeforeEach void clear() { BuyerAccountAcceptanceTest.sent.clear(); }

  @Test void aCaseCreatedWhileSignedInIsSavedAndTheListNamesIt() throws Exception {
    try (var app = start("create")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");

      var saved = create(client, session, true);
      assertThat(saved.status()).isEqualTo(201);
      assertThat(saved.body().path("savedToAccount").asBoolean()).isTrue();
      String caseId = saved.body().at("/case/id").asText(), caseToken = saved.body().path("accessToken").asText();
      var vehicle = call(client, "POST", "/api/v1/cases/" + caseId + "/vehicle-evidence", Map.of("confirmed", true,
          "manualFacts", Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000)), null, caseToken);
      assertThat(vehicle.status()).isEqualTo(200);

      var listed = account(client, session).path("cases");
      assertThat(listed.size()).isEqualTo(1);
      assertThat(listed.at("/0/caseId").asText()).isEqualTo(caseId);
      assertThat(listed.at("/0/state").asText()).isEqualTo("ACTIVE");
      assertThat(listed.at("/0/vehicle").asText()).isEqualTo("2021 Honda Accord EX-L");
      assertThat(listed.at("/0/dealer").asText()).isEqualTo("Dealer ZIP 27604");
      assertThat(listed.at("/0/startedAt").asText()).isNotBlank();

      // Without the account request header, or signed out, a new case stays unattached and can be attached by hand.
      var unsaid = create(client, session, false);
      assertThat(unsaid.body().path("savedToAccount").asBoolean()).isFalse();
      var signedOut = create(client, null, true);
      assertThat(signedOut.status()).isEqualTo(201);
      assertThat(signedOut.body().path("savedToAccount").asBoolean()).isFalse();
      assertThat(account(client, session).path("cases").size()).isEqualTo(1);
      var attached = call(client, "POST", "/api/v1/account/cases", Map.of("caseId", signedOut.body().at("/case/id").asText()),
          session, signedOut.body().path("accessToken").asText());
      assertThat(attached.status()).isEqualTo(200);
      assertThat(attached.body().path("cases").size()).isEqualTo(2);
      assertThat(attached.body().at("/cases/1/vehicle").isNull()).isTrue();

      // An ended session does not refuse case creation; the case is simply not saved.
      call(client, "POST", "/api/v1/account/sign-out", Map.of(), session, null);
      var afterSignOut = create(client, session, true);
      assertThat(afterSignOut.status()).isEqualTo(201);
      assertThat(afterSignOut.body().path("savedToAccount").asBoolean()).isFalse();
    }
  }

  @Test void anAttachedCaseOpensWithTheSessionAndNotForAnyoneElse() throws Exception {
    try (var app = start("open")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      String other = signIn(client, "other@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText(), caseToken = created.path("accessToken").asText();
      var foreign = create(client, other, true).body();
      String foreignId = foreign.at("/case/id").asText();

      var opened = open(client, session, caseId);
      assertThat(opened.status()).isEqualTo(200);
      String pass = opened.body().path("accessToken").asText();
      assertThat(pass).startsWith("acct.").doesNotContain(caseToken);
      assertThat(opened.body().path("accessExpiresAt").asText()).isNotBlank();

      // The pass works for reads and writes on this case with this session's cookie, and only then.
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, session, pass).body().path("id").asText()).isEqualTo(caseId);
      assertThat(call(client, "POST", "/api/v1/cases/" + caseId + "/vehicle-evidence", Map.of("confirmed", true,
          "manualFacts", Map.of("year", 2020, "make", "Toyota", "model", "Camry", "mileage", 41000)), session, pass).status()).isEqualTo(200);
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, null, pass).status()).isEqualTo(404);
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, other, pass).status()).isEqualTo(404);
      assertThat(call(client, "GET", "/api/v1/cases/" + foreignId, null, session, pass).status()).isEqualTo(404);
      String forged = pass.substring(0, pass.lastIndexOf('.') + 1) + "0".repeat(64);
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, session, forged).status()).isEqualTo(404);

      // Another account's case, an unknown case and a signed-out request are refused.
      var elsewhere = open(client, session, foreignId);
      var unknown = open(client, session, UUID.randomUUID().toString());
      assertThat(elsewhere.status()).isEqualTo(404);
      JsonNode elsewhereProblem = elsewhere.body().deepCopy(), unknownProblem = unknown.body().deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) elsewhereProblem).remove("instance");
      ((com.fasterxml.jackson.databind.node.ObjectNode) unknownProblem).remove("instance");
      assertThat(elsewhereProblem.toString()).isEqualTo(unknownProblem.toString());
      assertThat(elsewhere.body().path("code").asText()).isEqualTo("CASE_UNAVAILABLE");
      assertThat(open(client, null, caseId).status()).isEqualTo(401);
      assertThat(open(client, "not-a-session", caseId).status()).isEqualTo(401);

      // Signing out ends access through the account; the case link keeps working.
      call(client, "POST", "/api/v1/account/sign-out", Map.of(), session, null);
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, session, pass).status()).isEqualTo(404);
      assertThat(open(client, session, caseId).status()).isEqualTo(401);
      assertThat(get(client, "/api/v1/cases/" + caseId, caseToken).path("id").asText()).isEqualTo(caseId);
    }
  }

  @Test void expiredSessionsAndUnavailableCasesCannotBeOpened() throws Exception {
    try (var app = start("unavailable", "--case.deletion.delay=PT1H")) {
      var client = app.client();
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      String session = signIn(client, "buyer@example.com");
      var expiring = create(client, session, true).body();
      String expiringId = expiring.at("/case/id").asText();
      var deleting = create(client, session, true).body();
      String deletingId = deleting.at("/case/id").asText(), deletingToken = deleting.path("accessToken").asText();
      var kept = create(client, session, true).body();
      String keptId = kept.at("/case/id").asText(), keptToken = kept.path("accessToken").asText();
      String deletingPass = open(client, session, deletingId).body().path("accessToken").asText();

      jdbc.update("update analysis_cases set access_expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)), expiringId);
      assertThat(call(client, "POST", "/api/v1/cases/" + deletingId + "/deletion", Map.of(), null, deletingToken).status()).isEqualTo(202);
      var cases = account(client, session).path("cases");
      assertThat(cases.findValuesAsText("state")).containsExactly("EXPIRED", "DELETION_PENDING", "ACTIVE");
      assertThat(open(client, session, expiringId).body().path("code").asText()).isEqualTo("CASE_UNAVAILABLE");
      assertThat(open(client, session, deletingId).body().path("code").asText()).isEqualTo("CASE_UNAVAILABLE");
      assertThat(call(client, "GET", "/api/v1/cases/" + deletingId, null, session, deletingPass).status()).isEqualTo(404);

      // An expired session is signed out for account access; the case link still opens the case.
      String keptPass = open(client, session, keptId).body().path("accessToken").asText();
      jdbc.update("update buyer_account_sessions set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
      assertThat(call(client, "GET", "/api/v1/cases/" + keptId, null, session, keptPass).status()).isEqualTo(404);
      assertThat(open(client, session, keptId).status()).isEqualTo(401);
      assertThat(get(client, "/api/v1/cases/" + keptId, keptToken).path("id").asText()).isEqualTo(keptId);
    }
  }

  @Test void accountDeletionEndsAccessThroughTheAccount() throws Exception {
    try (var app = start("deletion", "--case.deletion.delay=PT1H")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText();
      String pass = open(client, session, caseId).body().path("accessToken").asText();
      assertThat(call(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, session, pass).status()).isEqualTo(404);
      assertThat(open(client, session, caseId).status()).isEqualTo(401);
    }
  }

  @Test void detachmentEndsPassAccessWhileTheSessionAndCaseLinkStayActive() throws Exception {
    try (var app = start("detachment")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText(), link = created.path("accessToken").asText();
      String pass = open(client, session, caseId).body().path("accessToken").asText();
      assertPassWorks(client, session, caseId, pass);

      // Exercise attachment removal at the persistence lifecycle seam; there is no public detach route.
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.update("delete from buyer_account_cases where case_id = ?", caseId)).isEqualTo(1);
      assertThat(account(client, session).path("cases")).isEmpty();
      assertPassUnavailable(client, session, caseId, pass);
      assertSameUnavailable(open(client, session, caseId), open(client, session, UUID.randomUUID().toString()));
      assertPassWorks(client, null, caseId, link);
    }
  }

  @Test void verifiedRecoveryRotatesTheLinkAndInvalidatesTheOriginalPass() throws Exception {
    try (var app = start("pass-rotation")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText(), link = created.path("accessToken").asText();
      String pass = open(client, session, caseId).body().path("accessToken").asText();
      assertPassWorks(client, session, caseId, pass);
      assertThat(call(client, "POST", "/api/v1/cases/" + caseId + "/recovery-email",
          Map.of("email", "buyer@example.com"), session, pass).status()).isEqualTo(200);
      String secret = BuyerAccountAcceptanceTest.sent.getLast().link().split("#recover=")[1];
      // Enrollment alone does not rotate either credential.
      assertPassWorks(client, session, caseId, pass);
      var recovered = call(client, "POST", "/api/v1/recovery/verify", Map.of("token", secret), null, null);
      assertThat(recovered.status()).isEqualTo(200);
      assertThat(recovered.body().path("caseId").asText()).isEqualTo(caseId);
      String newLink = recovered.body().path("accessToken").asText();
      assertThat(newLink).isNotEqualTo(link);

      assertPassUnavailable(client, session, caseId, pass);
      assertPassUnavailable(client, null, caseId, link);
      assertThat(account(client, session).path("cases")).hasSize(1);
      var reopened = open(client, session, caseId);
      assertThat(reopened.status()).isEqualTo(200);
      String newPass = reopened.body().path("accessToken").asText();
      assertThat(newPass).isNotEqualTo(pass);
      assertPassWorks(client, session, caseId, newPass);
      assertPassWorks(client, null, caseId, newLink);
    }
  }

  @Test void thePassOwnExpiryEndsAccessWithoutEndingTheSessionOrCase() throws Exception {
    try (var app = start("pass-expiry", "--account.case-pass.lifetime=PT0S")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText(), link = created.path("accessToken").asText();
      var opened = open(client, session, caseId);
      assertThat(opened.status()).isEqualTo(200);
      assertThat(Instant.parse(opened.body().path("accessExpiresAt").asText())).isBeforeOrEqualTo(Instant.now());
      assertPassUnavailable(client, session, caseId, opened.body().path("accessToken").asText());
      assertThat(account(client, session).at("/cases/0/state").asText()).isEqualTo("ACTIVE");
      assertPassWorks(client, null, caseId, link);
    }
  }

  @Test void substitutedSessionsCasesAndMalformedPassFieldsCannotReadOrWrite() throws Exception {
    try (var app = start("pass-fields")) {
      var client = app.client();
      String session = signIn(client, "buyer@example.com");
      String secondSession = signIn(client, "buyer@example.com");
      String otherAccount = signIn(client, "other@example.com");
      var created = create(client, session, true).body();
      String caseId = created.at("/case/id").asText(), link = created.path("accessToken").asText();
      String sameAccountCase = create(client, session, true).body().at("/case/id").asText();
      String foreignCase = create(client, otherAccount, true).body().at("/case/id").asText();
      String pass = open(client, session, caseId).body().path("accessToken").asText();
      assertPassWorks(client, session, caseId, pass);
      for (String substitute : new String[] {null, secondSession, otherAccount})
        assertPassUnavailable(client, substitute, caseId, pass);
      assertPassUnavailable(client, session, sameAccountCase, pass);
      assertPassUnavailable(client, session, foreignCase, pass);
      assertPassWorks(client, secondSession, caseId, open(client, secondSession, caseId).body().path("accessToken").asText());

      String[] parts = pass.split("\\.");
      for (String malformed : new String[] {
          "acct.", "acct.." + parts[2] + "." + parts[3],
          "acct." + caseId + "." + parts[3], pass + ".extra",
          "acct." + caseId + ".not-an-epoch." + parts[3],
          "acct." + caseId + ".9223372036854775808." + parts[3],
          "acct." + caseId + "." + Long.MAX_VALUE + "." + parts[3],
          "acct." + caseId + "." + Long.MIN_VALUE + "." + parts[3],
          "acct." + caseId + "." + (Long.parseLong(parts[2]) + 3600) + "." + parts[3],
          "acct." + caseId + ".0." + parts[3],
          "acct." + sameAccountCase + "." + parts[2] + "." + parts[3],
          "acct." + caseId + "." + parts[2] + ".", pass.substring(0, pass.length() - 1)})
        assertPassUnavailable(client, session, caseId, malformed);

      assertPassWorks(client, session, caseId, pass);
      call(client, "POST", "/api/v1/account/sign-out", Map.of(), session, null);
      assertPassUnavailable(client, session, caseId, pass);
      assertPassWorks(client, secondSession, caseId, open(client, secondSession, caseId).body().path("accessToken").asText());
      assertPassWorks(client, null, caseId, link);
    }
  }

  private static Map<String, Object> vehicleFacts() {
    return Map.of("confirmed", true, "manualFacts", Map.of("year", 2020, "make", "Toyota", "model", "Camry", "mileage", 41000));
  }

  private static void assertPassWorks(RestClient client, String session, String caseId, String pass) {
    assertThat(call(client, "GET", "/api/v1/cases/" + caseId, null, session, pass).body().path("id").asText()).isEqualTo(caseId);
    assertThat(call(client, "POST", "/api/v1/cases/" + caseId + "/vehicle-evidence", vehicleFacts(), session, pass).status()).isEqualTo(200);
  }

  private static void assertPassUnavailable(RestClient client, String session, String caseId, String pass) {
    String unknown = UUID.randomUUID().toString();
    assertSameUnavailable(call(client, "GET", "/api/v1/cases/" + caseId, null, session, pass),
        call(client, "GET", "/api/v1/cases/" + unknown, null, session, pass));
    assertSameUnavailable(call(client, "POST", "/api/v1/cases/" + caseId + "/vehicle-evidence", vehicleFacts(), session, pass),
        call(client, "POST", "/api/v1/cases/" + unknown + "/vehicle-evidence", vehicleFacts(), session, pass));
  }

  private static void assertSameUnavailable(Result actual, Result unknown) {
    assertThat(actual.status()).isEqualTo(404);
    assertThat(unknown.status()).isEqualTo(404);
    var actualProblem = (com.fasterxml.jackson.databind.node.ObjectNode) actual.body().deepCopy();
    var unknownProblem = (com.fasterxml.jackson.databind.node.ObjectNode) unknown.body().deepCopy();
    actualProblem.remove("instance");
    unknownProblem.remove("instance");
    assertThat(actualProblem).isEqualTo(unknownProblem);
  }

  private RunningApplication start(String name, String... arguments) throws Exception {
    var all = new String[arguments.length + 1];
    all[0] = KEY;
    System.arraycopy(arguments, 0, all, 1, arguments.length);
    return new HttpAcceptanceHarness(directory).startWithConfiguration(name, false, BuyerAccountAcceptanceTest.EmailConfiguration.class, all);
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    return AccountSessionTestSupport.verify(client, BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1]);
  }

  private static JsonNode account(RestClient client, String session) {
    var result = call(client, "GET", "/api/v1/account", null, session, null);
    assertThat(result.status()).isEqualTo(200);
    return result.body();
  }

  private static Result create(RestClient client, String session, boolean accountRequest) {
    var request = client.post().uri("/api/v1/cases").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).body(CasePortabilityAcceptanceTest.buyingCase());
    if (session != null) request.header("Cookie", AccountSessionTestSupport.cookie(session));
    if (accountRequest) request.header("Account-Request", "1");
    return request.exchange((req, response) -> new Result(response.getStatusCode().value(), JSON.readTree(response.getBody())));
  }

  private static Result open(RestClient client, String session, String caseId) {
    return call(client, "POST", "/api/v1/account/cases/" + caseId + "/open", null, session, null);
  }

  record Result(int status, JsonNode body) {}

  private static Result call(RestClient client, String method, String path, Object body, String session, String caseToken) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path);
    if (!method.equals("GET")) request.header("Idempotency-Key", UUID.randomUUID().toString()).header("Account-Request", "1");
    if (session != null) request.header("Cookie", AccountSessionTestSupport.cookie(session));
    if (caseToken != null) request.header("Case-Access-Token", caseToken);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }
}
