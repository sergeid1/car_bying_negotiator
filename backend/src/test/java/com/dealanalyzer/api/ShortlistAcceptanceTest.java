package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class ShortlistAcceptanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final CopyOnWriteArrayList<String> links = new CopyOnWriteArrayList<>();
  static final String SESSION = "Account-Session-Token";
  @TempDir Path directory;

  @TestConfiguration static class Configuration {
    @Bean @Primary RecoveryEmail shortlistEmail() {
      return new RecoveryEmail() {
        public void send(String email, String link) { links.add(link); }
        @Override public void send(String email, String link, Purpose purpose) { links.add(link); }
      };
    }
  }

  @BeforeEach void clear() { links.clear(); }

  @Test void shortlistSurvivesRestartAndOpensIndependentCasesWithCapturedEvidence() throws Exception {
    String session, journeyId, firstId, secondId, firstCaseId, firstToken;
    JsonNode firstCandidate;
    try (var app = start("persisted-shortlist")) {
      assertThat(app.context().getBean(com.dealanalyzer.quote.JourneyAgreement.class))
          .isInstanceOf(com.dealanalyzer.journey.LinkedJourneyAgreement.class);
      var client = app.client(); session = signIn(client, "buyer@example.com");
      journeyId = journey(client, session, "journey"); String base = "/api/v1/journeys/" + journeyId;
      var found = exchange(client, "POST", base + "/candidates", null, session, null);
      assertThat(found.status()).isEqualTo(200);
      var candidates = found.body().path("candidates"); assertThat(candidates.size()).isGreaterThanOrEqualTo(2);
      firstCandidate = candidates.get(0); firstId = firstCandidate.path("candidateId").asText();
      secondId = candidates.get(1).path("candidateId").asText();
      assertThat(firstId).isNotBlank(); assertThat(secondId).isNotBlank().isNotEqualTo(firstId);
      assertThat(firstCandidate.at("/marketCheck/confidence").asText()).isNotBlank();
      assertThat(firstCandidate.at("/marketCheck/provider/retrievedAt").asText()).isNotBlank();
      assertThat(firstCandidate.path("advertisedPriceMinor").asLong()).isPositive();
      assertThat(exchange(client, "GET", base + "/shortlist", null, session, null).body()).isEmpty();
      setShortlist(client, base, session, List.of(firstId, secondId));
      assertThat(setShortlist(client, base, session, List.of(secondId, firstId)).findValuesAsText("candidateId"))
          .containsExactly(secondId, firstId);
      assertThat(setShortlist(client, base, session, List.of(firstId)).findValuesAsText("candidateId"))
          .containsExactly(firstId);
      setShortlist(client, base, session, List.of(secondId, firstId));

      String foreign = signIn(client, "other@example.com");
      for (String method : List.of("GET", "PUT"))
        assertThat(exchange(client, method, base + "/shortlist", method.equals("PUT")
            ? Map.of("candidateIds", List.of(firstId)) : null, foreign, null).status()).isEqualTo(404);
      assertThat(exchange(client, "POST", base + "/candidates/" + firstId + "/case",
          CasePortabilityAcceptanceTest.buyingCase(), foreign, "foreign-case").status()).isEqualTo(404);
      var invalid = new HashMap<>(CasePortabilityAcceptanceTest.buyingCase()); invalid.remove("purchaseMethod");
      assertThat(exchange(client, "POST", base + "/candidates/" + firstId + "/case", invalid, session, "invalid").status())
          .isEqualTo(400);
      var created = openCase(client, base, firstId, session, "open-first");
      firstCaseId = created.at("/case/id").asText(); firstToken = created.path("accessToken").asText();
      assertThat(firstCaseId).isNotBlank(); assertThat(firstToken).isNotBlank();
      assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
      assertThat(created.at("/case/dealerLocation").asText()).isEqualTo("27604");
      assertThat(created.at("/case/purchaseMethod").asText()).isEqualTo("CASH");
      assertThat(created.at("/case/consents").findValuesAsText("purpose")).contains("SERVICE_PROCESSING");
      assertThat(openCase(client, base, firstId, session, "open-first").at("/case/id").asText()).isEqualTo(firstCaseId);
      var reopened = exchange(client, "GET", base + "/candidates/" + firstId + "/case", null, session, null);
      assertThat(reopened.status()).isEqualTo(200);
      assertThat(reopened.body().at("/case/id").asText()).isEqualTo(firstCaseId);
      assertThat(reopened.body().path("accessToken").asText()).isEqualTo(firstToken);
      assertThat(exchange(client, "GET", base + "/candidates/" + firstId + "/case", null, foreign, null).status()).isEqualTo(404);
      var second = openCase(client, base, secondId, session, "open-second");
      assertThat(second.at("/case/id").asText()).isNotEqualTo(firstCaseId);
      var jdbc = jdbc(app);
      assertThat(jdbc.queryForObject("select count(*) from journey_candidate_cases where journey_id = ?", Long.class, journeyId)).isEqualTo(2);
      assertThat(jdbc.queryForObject("select count(*) from buyer_account_cases", Long.class)).isEqualTo(2);
      JsonNode incomplete = null;
      for (var candidate : candidates) if (candidate.path("listingId").asText().equals("missing-facts")) incomplete = candidate;
      assertThat(incomplete).isNotNull();
      var incompleteCase = openCase(client, base, incomplete.path("candidateId").asText(), session, "incomplete");
      var incompleteEvidence = get(client, "/api/v1/cases/" + incompleteCase.at("/case/id").asText() + "/vehicle-evidence",
          incompleteCase.path("accessToken").asText());
      assertThat(incompleteEvidence.path("reliableForAnalysis").asBoolean()).isFalse();
      assertThat(incompleteEvidence.path("status").asText()).isEqualTo("VEHICLE_CONFIRMATION_REQUIRED");
      assertThat(get(client, "/api/v1/cases/" + firstCaseId, firstToken).path("id").asText()).isEqualTo(firstCaseId);
      assertEvidence(client, firstCaseId, firstToken, firstCandidate);
    }
    try (var app = start("persisted-shortlist")) {
      var client = app.client(); String base = "/api/v1/journeys/" + journeyId;
      var loaded = exchange(client, "GET", base + "/shortlist", null, session, null).body();
      assertThat(loaded.findValuesAsText("candidateId")).containsExactly(secondId, firstId);
      assertThat(loaded.get(1).path("caseId").asText()).isEqualTo(firstCaseId);
      assertThat(loaded.get(1).path("candidate").path("snapshot")).isEqualTo(firstCandidate.path("snapshot"));
      assertEvidence(client, firstCaseId, firstToken, firstCandidate);
      var agreement = app.context().getBean(com.dealanalyzer.quote.JourneyAgreement.class);
      var transaction = new org.springframework.transaction.support.TransactionTemplate(
          app.context().getBean(org.springframework.transaction.PlatformTransactionManager.class));
      var agreed = transaction.execute(status -> agreement.agree(firstCaseId));
      assertThat(agreed.journeyLinked()).isTrue();
      var journey = exchange(client, "GET", base, null, session, null).body();
      assertThat(journey.path("state").asText()).isEqualTo("AGREED");
      var updatedAt = journey.path("updatedAt");
      assertThat(transaction.execute(status -> agreement.current(firstCaseId, agreed)).journeyLinked()).isTrue();
      assertThat(exchange(client, "GET", base, null, session, null).body().path("updatedAt")).isEqualTo(updatedAt);
    }
  }

  @Test void exportsIncludeLinksAndDeletionRemovesOnlyTheRequestedGraphs() throws Exception {
    try (var app = start("shortlist-privacy")) {
      var client = app.client(); String session = signIn(client, "buyer@example.com");
      String journeyId = journey(client, session, "first"); String base = "/api/v1/journeys/" + journeyId;
      var candidate = exchange(client, "POST", base + "/candidates", null, session, null).body().path("candidates").get(0);
      String candidateId = candidate.path("candidateId").asText(); setShortlist(client, base, session, List.of(candidateId));
      var created = openCase(client, base, candidateId, session, "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      byte[] archive = client.post().uri("/api/v1/account/export").header(SESSION, session).retrieve().body(byte[].class);
      assertThat(zipEntries(archive).get("account.json")).contains(journeyId, candidateId, caseId)
          .doesNotContain(token, "credential_ciphertext");
      var job = post(client, "/api/v1/cases/" + caseId + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "case-export", token);
      byte[] caseArchive = client.get().uri("/api/v1/cases/" + caseId + "/exports/" + job.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      var caseData = JSON.readTree(zipEntries(caseArchive).get("case-data.json"));
      assertThat(caseData.toString()).doesNotContain(token, "credential_ciphertext");
      assertThat(caseData.path("journey_candidate_cases")).hasSize(1);
      assertThat(caseData.at("/journey_candidate_cases/0/candidate_id").asText()).isEqualTo(candidateId);
      post(client, "/api/v1/cases/" + caseId + "/deletion", Map.of(), "delete-case", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      var jdbc = jdbc(app);
      assertThat(jdbc.queryForObject("select count(*) from journey_candidate_cases where case_id = ?", Long.class, caseId)).isZero();
      assertThat(exchange(client, "GET", base + "/shortlist", null, session, null).body()).hasSize(1);
      assertThat(exchange(client, "DELETE", base, null, session, null).status()).isEqualTo(200);
      assertThat(jdbc.queryForObject("select count(*) from journey_candidates where journey_id = ?", Long.class, journeyId)).isZero();

      String retainedJourney = journey(client, session, "retained"); String retainedBase = "/api/v1/journeys/" + retainedJourney;
      String retainedCandidate = exchange(client, "POST", retainedBase + "/candidates", null, session, null)
          .body().at("/candidates/0/candidateId").asText();
      openCase(client, retainedBase, retainedCandidate, session, "retained-case");
      String other = signIn(client, "other@example.com"); String otherJourney = journey(client, other, "other");
      exchange(client, "POST", "/api/v1/journeys/" + otherJourney + "/candidates", null, other, null);
      assertThat(exchange(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      for (String table : List.of("journey_candidates", "journey_candidate_cases"))
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where journey_id = ?", Long.class, retainedJourney)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from journey_candidates where journey_id = ?", Long.class, otherJourney)).isPositive();
      assertThat(exchange(client, "GET", "/api/v1/journeys/" + otherJourney, null, other, null).status()).isEqualTo(200);
    }
  }

  private void assertEvidence(RestClient client, String id, String token, JsonNode candidate) throws Exception {
    var evidence = get(client, "/api/v1/cases/" + id + "/vehicle-evidence", token);
    for (String field : List.of("year", "make", "model", "trim", "mileage", "vin"))
      assertThat(evidence.path("vehicle").path(field)).as(field).isEqualTo(candidate.path(field));
    assertThat(evidence.at("/listingSnapshot/advertisedPriceMinor")).isEqualTo(candidate.path("advertisedPriceMinor"));
    assertThat(evidence.at("/listingSnapshot/source")).isEqualTo(candidate.at("/snapshot/source"));
    assertThat(evidence.at("/listingSnapshot/retrievedAt")).isEqualTo(candidate.at("/snapshot/retrievedAt"));
  }

  private HttpAcceptanceHarness.RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--case.deletion.delay=PT1H");
  }
  private static JdbcTemplate jdbc(HttpAcceptanceHarness.RunningApplication app) {
    return new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
  }
  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    return post(client, "/api/v1/account/sign-in/verify", Map.of("token", links.getLast().split("#sign-in=")[1]))
        .path("sessionToken").asText();
  }
  private static String journey(RestClient client, String session, String key) {
    var result = exchange(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, key);
    assertThat(result.status()).isEqualTo(201); return result.body().path("id").asText();
  }
  private static JsonNode setShortlist(RestClient client, String base, String session, List<String> ids) {
    var result = exchange(client, "PUT", base + "/shortlist", Map.of("candidateIds", ids), session, null);
    assertThat(result.status()).isEqualTo(200); return result.body();
  }
  private static JsonNode openCase(RestClient client, String base, String candidate, String session, String key) {
    var result = exchange(client, "POST", base + "/candidates/" + candidate + "/case",
        CasePortabilityAcceptanceTest.buyingCase(), session, key);
    assertThat(result.status()).isEqualTo(201); return result.body();
  }
  private static Map<String, String> zipEntries(byte[] archive) throws Exception {
    var result = new HashMap<String, String>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
        if (entry.getName().endsWith(".json")) result.put(entry.getName(), new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    return result;
  }
  record Result(int status, JsonNode body) {}
  private static Result exchange(RestClient client, String method, String path, Object body, String session, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path);
    if (session != null) request.header(SESSION, session);
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((req, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }
}
