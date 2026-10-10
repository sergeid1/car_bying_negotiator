package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Case isolation as a build property. Every registered endpoint is classified here; a case-scoped endpoint is
 * called with no, a malformed, an expired and another case's credential, and with another case's object IDs,
 * and each refusal must match the response for an unknown case or object byte for byte.
 */
class CaseIsolationInventoryAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SUPPORT = "support-secret-with-at-least-thirty-two-bytes";
  @TempDir Path directory;
  private static final java.util.concurrent.atomic.AtomicInteger comparisons = new java.util.concurrent.atomic.AtomicInteger();

  enum Scope { CASE, ACCOUNT, OPERATOR, PUBLIC }

  /**
   * One registered endpoint. Path placeholders resolve through {@code ids} (placeholder=objectKey) and
   * otherwise by name; the body may name object keys as {key}. A minimal body reaches the authorization check.
   */
  record Endpoint(String method, String path, Scope scope, String body, Map<String, String> ids, String reason) {
    String key() { return method + " " + path; }
  }

  private static final String V = "/api/v1";
  private static final String C = V + "/cases/{caseId}";
  private static final String MULTIPART = "<multipart>";
  private static final String CASE_BODY = "{\"buyerPostalCode\":\"27601\",\"dealerPostalCode\":\"27604\",\"purchaseMethod\":\"CASH\","
      + "\"consents\":{\"serviceProcessing\":{\"granted\":true,\"policyVersion\":\"service.v1\"},"
      + "\"humanReview\":{\"granted\":false,\"policyVersion\":\"human-review.v1\"},"
      + "\"anonymizedResearch\":{\"granted\":false,\"policyVersion\":\"research.v1\"}}}";

  private static Endpoint caseRow(String method, String path, String body, String... ids) {
    var aliases = new LinkedHashMap<String, String>();
    for (String id : ids) aliases.put(id.substring(0, id.indexOf('=')), id.substring(id.indexOf('=') + 1));
    return new Endpoint(method, path, Scope.CASE, body, aliases, null);
  }
  private static Endpoint accountRow(String method, String path, String body, String... ids) {
    var row = caseRow(method, path, body, ids);
    return new Endpoint(method, path, Scope.ACCOUNT, body, row.ids(), null);
  }
  private static Endpoint publicRow(String method, String path, String reason) {
    return new Endpoint(method, path, Scope.PUBLIC, null, Map.of(), reason);
  }

  /** Every endpoint that takes a Case-Access-Token or a {caseId}, with a body that reaches its access check. */
  static final List<Endpoint> CASE_ENDPOINTS = List.of(
      caseRow("GET", C, null),
      caseRow("POST", C + "/consents", "{\"purpose\":\"HUMAN_REVIEW\",\"granted\":false,\"policyVersion\":\"human-review.v1\"}"),
      caseRow("POST", C + "/analyses", "{}"),
      caseRow("POST", C + "/finance-offers", "{}"),
      caseRow("GET", C + "/finance-offers/latest", null),
      caseRow("POST", C + "/market-assessments", "{}"),
      caseRow("GET", C + "/market-assessments/latest", null),
      caseRow("GET", C + "/market-assessments/{version}", null),
      caseRow("POST", C + "/cash-economics", "{}"),
      caseRow("GET", C + "/cash-economics/latest", null),
      caseRow("POST", C + "/dealer-offers", "{\"extractionId\":\"{extractionId}\",\"confirmedCandidateIds\":[],\"confirmedLineItemIds\":[]}"),
      caseRow("GET", C + "/dealer-offers/latest", null),
      caseRow("GET", C + "/dealer-offers/{version}", null),
      caseRow("POST", C + "/documents", MULTIPART),
      caseRow("GET", C + "/documents", null),
      caseRow("GET", C + "/documents/{documentId}", null),
      caseRow("GET", C + "/documents/{documentId}/pages/{pageNumber}/image", null),
      caseRow("GET", C + "/documents/{documentId}/extractions", null),
      caseRow("GET", C + "/documents/{documentId}/extractions/latest", null),
      caseRow("GET", C + "/documents/{documentId}/extractions/{version}", null),
      caseRow("POST", C + "/documents/{documentId}/extractions/{extractionId}/corrections",
          "{\"targetType\":\"CANDIDATE\",\"targetId\":\"{candidateId}\",\"normalizedValue\":\"2680000\",\"normalizedUnit\":\"USD_MINOR\"}"),
      caseRow("GET", V + "/operations/{operationId}", null),
      caseRow("POST", V + "/operations/{operationId}/retries", "{}"),
      caseRow("POST", C + "/vehicle-evidence", "{\"confirmed\":true,\"manualFacts\":{\"year\":2021,\"make\":\"Honda\",\"model\":\"Accord\",\"mileage\":38000}}"),
      caseRow("GET", C + "/vehicle-evidence", null),
      caseRow("POST", C + "/vehicle-evidence/conflicts/{conflictId}/resolution", "{\"chosenValue\":\"x\"}"),
      caseRow("GET", C + "/reports/latest", null),
      caseRow("GET", C + "/reports/{version}", null),
      caseRow("GET", C + "/reports/{version}/explanation", null),
      caseRow("GET", C + "/reports/{version}/negotiation", null),
      caseRow("POST", C + "/reports/{version}/negotiation/replies", "{\"dealerResponse\":\"We can do 25000.\"}"),
      caseRow("GET", C + "/deal-reviews/latest", null),
      caseRow("GET", C + "/deal-reviews/{version}", null),
      caseRow("GET", C + "/appointment", null),
      caseRow("GET", C + "/appointment/appointments/{appointmentId}/calendar", null),
      caseRow("POST", C + "/appointment/proposals", "{\"timeZone\":\"America/Chicago\",\"windows\":[]}"),
      caseRow("POST", C + "/appointment/bookings",
          "{\"proposalId\":\"{testDriveMessageId}\",\"messageId\":\"{messageId}\",\"option\":1,\"release\":[]}"),
      caseRow("POST", C + "/appointment/appointments/{appointmentId}/cancellation", "{}"),
      caseRow("POST", C + "/appointment/messages/{id}/approval", "{\"approved\":true,\"text\":\"x\"}", "id=bookingMessageId"),
      caseRow("GET", C + "/desk-check", null),
      caseRow("GET", C + "/desk-start", null),
      caseRow("POST", C + "/desk-start/result", "{\"route\":\"THIS_CASE\",\"typed\":{\"sellingPriceMinor\":1}}"),
      caseRow("GET", C + "/quote-acceptance", null),
      caseRow("GET", C + "/quote-acceptance/summary", null),
      caseRow("POST", C + "/quote-acceptance", "{\"quoteRequestId\":\"{quoteId}\",\"offerVersion\":1}"),
      caseRow("POST", C + "/quote-acceptance/{id}/approval", "{\"approved\":true,\"text\":\"x\"}", "id=acceptanceId"),
      caseRow("POST", C + "/quote-acceptance/other-negotiations/{otherCaseId}", "{\"action\":\"PAUSE\"}"),
      caseRow("GET", C + "/quote-requests", null),
      caseRow("POST", C + "/quote-requests", "{\"dealerId\":\"{dealerId}\",\"dealerAddress\":\"sales@dealer.example\"}"),
      caseRow("POST", C + "/quote-requests/{id}/approval", "{\"approved\":true,\"text\":\"x\"}", "id=quoteId"),
      caseRow("GET", C + "/negotiation-conversation", null),
      caseRow("POST", C + "/negotiation-conversation/drafts", "{\"quoteRequestId\":\"{quoteId}\"}"),
      caseRow("POST", C + "/negotiation-conversation/drafts/{id}/revisions", "{\"text\":\"x\"}", "id=conversationDraftId"),
      caseRow("POST", C + "/negotiation-conversation/drafts/{id}/approval", "{\"approved\":true,\"text\":\"x\"}", "id=conversationDraftId"),
      caseRow("POST", C + "/negotiation-conversation/drafts/{id}/decline", "{}", "id=conversationDraftId"),
      caseRow("POST", C + "/negotiation-conversation/stop", "{}"),
      caseRow("POST", C + "/negotiation-conversation/resume", "{}"),
      caseRow("POST", C + "/negotiation-conversation/dealer-declined", "{}"),
      caseRow("DELETE", C + "/negotiation-conversation/dealer-declined", null),
      caseRow("POST", C + "/price-plans", "{}"),
      caseRow("GET", C + "/price-plans/{version}", null),
      caseRow("POST", C + "/approved-baselines", "{\"planVersion\":1,\"approved\":true}"),
      caseRow("GET", C + "/approved-baselines/{version}", null),
      caseRow("GET", C + "/mandates", null),
      caseRow("GET", C + "/mandates/proposal", null),
      caseRow("POST", C + "/mandates", "{\"planVersion\":1,\"targetMinor\":1,\"ceilingMinor\":2,\"approved\":true}"),
      caseRow("GET", C + "/mandates/{version}", null),
      caseRow("POST", C + "/mandates/{version}/revocation", "{}"),
      caseRow("POST", C + "/human-reviews", "{\"extractionId\":\"{extractionId}\",\"candidateId\":\"{candidateId}\"}"),
      caseRow("GET", C + "/human-reviews", null),
      caseRow("GET", C + "/entitlement", null),
      caseRow("POST", C + "/purchases", "{\"offerId\":\"one-time-case.v1\"}"),
      caseRow("POST", C + "/exports", "{\"format\":\"ZIP\",\"reportVersion\":0}"),
      caseRow("GET", C + "/exports/{id}", null, "id=exportId"),
      caseRow("POST", V + "/cases/{id}/recovery-email", "{\"email\":\"recover@example.com\"}", "id=caseId"),
      caseRow("POST", C + "/deletion", "{}"),
      caseRow("GET", C + "/deletion", null),
      caseRow("POST", C + "/retention", "{\"days\":7}"),
      caseRow("POST", C + "/feedback/findings",
          "{\"reportVersion\":1,\"findingPointer\":\"/analysis/reviewStatus\",\"accurate\":\"YES\",\"understandable\":\"YES\",\"useful\":\"YES\"}"),
      caseRow("POST", C + "/feedback/outcomes", "{\"stage\":\"FINAL\"}"),
      caseRow("GET", C + "/feedback", null),
      caseRow("POST", C + "/feedback/report-total", "{\"reportVersion\":1}"),
      caseRow("POST", C + "/feedback/report-total/{id}", "{\"answerMinor\":1,\"unknown\":false,\"durationMillis\":1}", "id=reportTotalId"),
      caseRow("POST", C + "/outside-finance-offers", "{}"),
      caseRow("GET", C + "/outside-finance-offers/latest", null),
      caseRow("GET", C + "/outside-finance-offers/{version}", null),
      caseRow("POST", C + "/outbound-drafts", "{\"dealerId\":\"{dealerId}\",\"text\":\"Is the vehicle still available?\"}"),
      caseRow("GET", C + "/outbound-drafts", null),
      caseRow("GET", C + "/outbound-drafts/{draftId}", null),
      caseRow("GET", C + "/relay", null),
      caseRow("GET", C + "/relay/messages/{messageId}", null),
      caseRow("GET", C + "/handoffs/{messageId}", null),
      caseRow("POST", C + "/handoffs/{messageId}/sent", "{}"),
      caseRow("POST", C + "/handoffs/{messageId}/discard", "{}"),
      caseRow("POST", C + "/handoffs/{messageId}/own-email-app", "{}"),
      caseRow("POST", C + "/handoffs/{messageId}/mailbox-send", "{}"),
      caseRow("POST", C + "/quote-requests/{id}/replies", MULTIPART, "id=quoteId"),
      caseRow("GET", C + "/trade", null),
      caseRow("PUT", C + "/trade", "{\"vin\":\"2T3P1RFV5MW000001\",\"mileage\":1,\"condition\":\"GOOD\"}"),
      caseRow("DELETE", C + "/trade", null),
      caseRow("POST", C + "/trade/offers", MULTIPART),
      caseRow("DELETE", C + "/trade/offers/{offerId}", null, "offerId=tradeOfferId"),
      caseRow("GET", C + "/trade/offers/{offerId}/pages/{pageNumber}/image", null, "offerId=tradeOfferId"),
      caseRow("POST", C + "/trade/carry-over", "{\"sourceCaseId\":\"{otherCaseId}\"}"));

  /** Buyer Account endpoints: the account_session cookie, plus Account-Request: 1 on writes. */
  static final List<Endpoint> ACCOUNT_ENDPOINTS = List.of(
      accountRow("GET", V + "/account", null),
      accountRow("PUT", V + "/account/contact", "{\"firstName\":\"Pat\"}"),
      accountRow("POST", V + "/account/cases", "{\"caseId\":\"{caseId}\"}"),
      accountRow("POST", V + "/account/cases/{caseId}/open", "{}"),
      accountRow("POST", V + "/account/sign-out", "{}"),
      accountRow("POST", V + "/account/export", "{}"),
      accountRow("POST", V + "/account/deletion", "{}"),
      accountRow("GET", V + "/account/deletion", null),
      accountRow("GET", V + "/account/notifications", null),
      accountRow("GET", V + "/account/passkeys", null),
      accountRow("GET", V + "/account/password", null),
      accountRow("PUT", V + "/account/password", "{\"password\":\"a long test phrase\"}"),
      accountRow("DELETE", V + "/account/password", null),
      accountRow("POST", V + "/account/passkeys/registration/options", "{}"),
      accountRow("POST", V + "/account/passkeys/registration", "{\"attemptId\":\"unknown\",\"nickname\":\"Device\",\"credential\":\"{}\"}"),
      accountRow("DELETE", V + "/account/passkeys/{id}", null, "id=passkeyId"),
      accountRow("GET", V + "/account/mailbox", null),
      accountRow("POST", V + "/account/mailbox/authorizations", "{\"provider\":\"GOOGLE\"}"),
      accountRow("POST", V + "/account/mailbox/connection", "{\"state\":\"unknown\",\"code\":\"unknown\"}"),
      accountRow("DELETE", V + "/account/mailbox/connection", null),
      accountRow("PUT", V + "/account/notifications", "{\"type\":\"APPOINTMENT\",\"enabled\":false}"),
      accountRow("GET", V + "/journeys", null),
      accountRow("POST", V + "/journeys", "{\"yearFrom\":2020,\"yearTo\":2024,\"makeId\":\"toyota\",\"modelId\":\"toyota-rav4\","
          + "\"trimIds\":[],\"maxMileage\":60000,\"priceCeiling\":32000,\"postalCode\":\"27601\",\"radiusMiles\":50,"
          + "\"mustHaveFeatures\":[],\"niceToHaveFeatures\":[]}"),
      accountRow("GET", V + "/journeys/brief-options", null),
      accountRow("GET", V + "/journeys/{journeyId}", null),
      accountRow("DELETE", V + "/journeys/{journeyId}", null),
      accountRow("POST", V + "/journeys/{journeyId}/briefs", "{\"basedOnVersion\":1,\"brief\":{}}"),
      accountRow("GET", V + "/journeys/{journeyId}/briefs/{version}", null),
      accountRow("GET", V + "/journeys/{journeyId}/briefs/{version}/reference-vehicles", null),
      accountRow("POST", V + "/journeys/{journeyId}/reference-vehicles", "{\"basedOnVersion\":1,\"vin\":\"1HGCM82633A004352\"}"),
      accountRow("PUT", V + "/journeys/{journeyId}/reference-vehicles/{referenceId}/relevance",
          "{\"basedOnVersion\":1,\"relevantAttributes\":[]}"),
      accountRow("DELETE", V + "/journeys/{journeyId}/reference-vehicles/{referenceId}", null),
      accountRow("POST", V + "/journeys/{journeyId}/candidates", "{}"),
      accountRow("GET", V + "/journeys/{journeyId}/shortlist", null),
      accountRow("PUT", V + "/journeys/{journeyId}/shortlist", "{\"candidateIds\":[]}"),
      accountRow("GET", V + "/journeys/{journeyId}/candidates/{candidateId}/case", null, "candidateId=journeyCandidateId"),
      accountRow("POST", V + "/journeys/{journeyId}/candidates/{candidateId}/case", CASE_BODY, "candidateId=journeyCandidateId"),
      accountRow("GET", V + "/journeys/{journeyId}/quote-comparison", null));

  /** Endpoints anyone may call, each with the reason it needs no credential. */
  static final List<Endpoint> PUBLIC_ENDPOINTS = List.of(
      publicRow("POST", V + "/cases", "Creates a case and issues its credential; the optional token only authorizes an idempotent replay."),
      publicRow("POST", V + "/cases/synthetic", "Fixture-backed case creation; the optional token only authorizes an idempotent replay."),
      publicRow("POST", V + "/recovery/requests", "Case recovery by email; the acknowledgement is identical whether or not anything matched."),
      publicRow("POST", V + "/recovery/verify", "Redeems a single-use emailed recovery secret, which is the credential."),
      publicRow("POST", V + "/email/ses-notifications", "Provider callback: configured SNS topic and verified AWS signature required before reading SES content; API peer rate limit applies."),
      publicRow("POST", V + "/account/sign-in-requests", "Buyer Account sign-in by email; the acknowledgement is identical for every address."),
      publicRow("POST", V + "/account/sign-in/verify", "Redeems a single-use emailed sign-in secret, which is the credential."),
      publicRow("POST", V + "/account/passkeys/sign-in/options", "Discoverable credential challenge with no account input or credential list."),
      publicRow("POST", V + "/account/passkeys/sign-in", "Redeems a single-use challenge with a verified credential signature and user handle."),
      publicRow("POST", V + "/account/password/sign-in", "Password verification with uniform failures, dummy hashing and account/client backoff."),
      publicRow("GET", V + "/offer", "The current one-time purchase offer, the same for everyone."),
      publicRow("GET", V + "/sample-report", "A redacted report built from a synthetic fixture case."),
      publicRow("GET", V + "/document-limits", "Upload limits the intake form shows before a case exists."),
      publicRow("GET", V + "/intake-options", "Served jurisdictions and consent policy versions for the intake form."),
      publicRow("*", "/error", "Spring Boot's error view; it renders failures of other requests."));


  /** Constant path values; every other placeholder names an object that belongs to one case or account. */
  private static final Map<String, String> CONSTANTS = Map.of("version", "1", "pageNumber", "1", "passkeyId", "00000000-0000-0000-0000-000000000095");
  private static final String MALFORMED = "not-a-case-token";

  /** A case, its credential and the IDs of the objects it owns, keyed as the endpoint table names them. */
  record Fixture(String id, String token, Map<String, String> objects) {}
  /** Credentials on one request: the case token, the account session cookie and the operator bearer token. */
  record Credentials(String caseToken, String session, String bearer) {
    static Credentials caseToken(String token) { return new Credentials(token, null, null); }
  }
  record Response(int status, Map<String, List<String>> headers, String body) {}

  @Test void everyEndpointIsClassifiedAndRefusesAnotherCasesCredentialOrObjects() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("isolation", true,
        AgreedPriceAcceptanceTest.Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--mailbox.token-encryption-key=mailbox-isolation-key-0123456789abcdef",
        "--security.rate-limit.requests=100000",
        "--security.request-email.peer-requests=100000",
        "--operations.accounts.support.token=" + SUPPORT, "--operations.accounts.support.role=SUPPORT")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var failures = new ArrayList<String>();
      comparisons.set(0);

      // Every registered endpoint has exactly one decision, and anything that takes a case credential is case-scoped.
      var inventory = inventory(app);
      var declared = new TreeMap<String, Scope>();
      for (var list : List.of(CASE_ENDPOINTS, ACCOUNT_ENDPOINTS, PUBLIC_ENDPOINTS))
        for (var row : list) assertThat(declared.put(row.key(), row.scope())).as("declared twice: " + row.key()).isNull();
      inventory.keySet().stream().filter(key -> key.contains(V + "/operator/")).forEach(key -> declared.put(key, Scope.OPERATOR));
      assertThat(new TreeSet<>(inventory.keySet())).as("Classify every new endpoint in " + getClass().getSimpleName()
          + " as case, account, operator or public; the difference is the unclassified or stale rows")
          .isEqualTo(declared.keySet());
      var caseScopedElsewhere = new TreeSet<String>();
      inventory.forEach((key, scoped) -> {
        if (scoped && Set.of(Scope.PUBLIC, Scope.ACCOUNT).contains(declared.get(key))) caseScopedElsewhere.add(key);
      });
      assertThat(caseScopedElsewhere).as("Only these take a case credential outside the case table, and each is tested below")
          .containsExactly("POST /api/v1/account/cases", "POST /api/v1/account/cases/{caseId}/open", "POST /api/v1/cases",
              "POST /api/v1/cases/synthetic");
      CASE_ENDPOINTS.forEach(row -> assertThat(inventory.get(row.key())).as("case row without a case credential: " + row.key()).isTrue());

      // Two cases with the full object graph, each owned by its own Buyer Account, and a fixture-backed case.
      var victim = agreedCase(app, "victim", "victim@example.com");
      var attacker = agreedCase(app, "attacker", "attacker@example.com");
      JsonNode golden;
      try (var input = getClass().getResourceAsStream("/fixtures/golden/cash-deal-standard.json")) { golden = JSON.readTree(input); }
      var started = post(client, V + "/cases/synthetic", golden.path("input"), "synthetic");
      awaitCompleted(client, started.at("/operation/id").asText(), started.path("accessToken").asText());
      var syntheticObjects = new HashMap<>(victim.objects());
      syntheticObjects.put("caseId", started.at("/case/id").asText());
      syntheticObjects.put("operationId", started.at("/operation/id").asText());
      var synthetic = new Fixture(started.at("/case/id").asText(), started.path("accessToken").asText(), syntheticObjects);
      String attackerSession = attacker.objects().get("session");

      // The comparison notices an accepted credential and an existence leak, so a pass below means something.
      var control = new ArrayList<String>();
      refusedLikeUnknown(client, CASE_ENDPOINTS.getFirst(), victim.objects(), "caseId", "control", Credentials.caseToken(victim.token()), control);
      assertThat(control).singleElement().asString().contains("was not refused");

      // No, a malformed, an expired or another case's credential is refused exactly like an unknown case.
      for (var target : List.of(victim, synthetic)) {
        for (var row : CASE_ENDPOINTS) {
          String session = row.path().contains("/appointment/") ? attackerSession : null;
          refusedLikeUnknown(client, row, target.objects(), primary(row), "no token", new Credentials(null, session, null), failures);
          generic(row, "malformed token", refusedLikeUnknown(client, row, target.objects(), primary(row), "malformed token",
              new Credentials(MALFORMED, session, null), failures), failures);
          generic(row, "another case's token", refusedLikeUnknown(client, row, target.objects(), primary(row), "another case's token",
              new Credentials(attacker.token(), session, null), failures), failures);
        }
        int expired = jdbc.update("update analysis_cases set access_expires_at = ? where id = ?",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), target.id());
        assertThat(expired).isEqualTo(1);
        for (var row : CASE_ENDPOINTS) {
          String session = row.path().contains("/appointment/") ? attackerSession : null;
          generic(row, "expired token", refusedLikeUnknown(client, row, target.objects(), primary(row), "expired token",
              new Credentials(target.token(), session, null), failures), failures);
        }
        jdbc.update("update analysis_cases set access_expires_at = ? where id = ?",
            java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(86400)), target.id());
        assertThat(get(client, V + "/cases/" + target.id(), target.token()).toString()).contains(target.id());
      }

      // The right case's credential with another case's object IDs is refused exactly like an unknown object.
      for (var row : CASE_ENDPOINTS) {
        var objects = new HashMap<>(attacker.objects());
        for (String key : nestedKeys(row)) {
          objects.put(key, victim.objects().get(key));
          refusedLikeUnknown(client, row, objects, key, "another case's " + key,
              new Credentials(attacker.token(), row.path().contains("/appointment/") ? attackerSession : null, null), failures);
          objects.put(key, attacker.objects().get(key));
        }
      }

      // A deletion receipt opens only with the credential the case had when deletion was requested.
      var doomed = post(client, V + "/cases", JSON.readTree(CASE_BODY), "doomed");
      String doomedId = doomed.at("/case/id").asText(), doomedToken = doomed.path("accessToken").asText();
      post(client, V + "/cases/" + doomedId + "/deletion", Map.of(), "delete-doomed", doomedToken);
      var deleted = Map.of("caseId", doomedId);
      for (var row : CASE_ENDPOINTS.stream().filter(row -> row.path().endsWith("/deletion")).toList()) {
        refusedLikeUnknown(client, row, deleted, "caseId", "deleted case, another token", Credentials.caseToken(attacker.token()), failures);
        refusedLikeUnknown(client, row, deleted, "caseId", "deleted case, no token", Credentials.caseToken(null), failures);
      }
      assertThat(get(client, V + "/cases/" + doomedId + "/deletion", doomedToken).path("state").asText()).isEqualTo("DELETION_PENDING");

      // Case creation replays need the original case's credential.
      var replay = call(client, "POST", V + "/cases", CASE_BODY, "victim-case", Credentials.caseToken(attacker.token()));
      if (replay.status() != 404 || replay.body().contains(victim.id()))
        failures.add("POST /api/v1/cases replay with another case's token: " + replay);
      var syntheticReplay = call(client, "POST", V + "/cases/synthetic", golden.path("input").toString(), "synthetic",
          Credentials.caseToken(attacker.token()));
      if (syntheticReplay.status() != 404 || syntheticReplay.body().contains(synthetic.id()))
        failures.add("POST /api/v1/cases/synthetic replay with another case's token: " + syntheticReplay);

      // Account endpoints refuse a missing session; journeys refuse another account's session like an unknown journey.
      for (var row : ACCOUNT_ENDPOINTS) {
        String requested = resolve(row.path(), row.ids(), victim.objects(), null);
        var none = call(client, row.method(), requested, resolve(row.body(), row.ids(), victim.objects(), null),
            UUID.randomUUID().toString(), new Credentials(victim.token(), null, null));
        // The deletion receipt outlives the account, so without a session it answers that no receipt is known.
        int refusal = row.key().equals("GET /api/v1/account/deletion") ? 404 : 401;
        // The problem body echoes the requested path as its instance; nothing else may name the case.
        if (none.status() != refusal || none.body().replace(requested, "").contains(victim.id())) failures.add(row.key() + " without a session: " + none);
        if (row.path().contains("{journeyId}")) {
          refusedLikeUnknown(client, row, victim.objects(), "journeyId", "another account's session",
              new Credentials(null, attackerSession, null), failures);
          for (String key : nestedKeys(row)) {
            if (key.equals("journeyId")) continue;
            var objects = new HashMap<>(attacker.objects());
            objects.put(key, victim.objects().get(key));
            refusedLikeUnknown(client, row, objects, key, "another account's " + key, new Credentials(null, attackerSession, null), failures);
          }
        }
      }
      var attach = ACCOUNT_ENDPOINTS.stream().filter(row -> row.key().equals("POST /api/v1/account/cases")).findFirst().orElseThrow();
      for (var token : Arrays.asList(null, MALFORMED, attacker.token()))
        refusedLikeUnknown(client, attach, victim.objects(), "caseId", "attach with " + token, new Credentials(token, attackerSession, null), failures);
      // Opening through the account refuses another account's case exactly like an unknown case, and the pass it
      // issues opens nothing without the session it was issued to.
      var open = ACCOUNT_ENDPOINTS.stream().filter(row -> row.key().equals("POST /api/v1/account/cases/{caseId}/open")).findFirst().orElseThrow();
      refusedLikeUnknown(client, open, victim.objects(), "caseId", "another account's case", new Credentials(null, attackerSession, null), failures);
      String victimSession = victim.objects().get("session");
      var opened = call(client, "POST", V + "/account/cases/" + victim.id() + "/open", "{}", null, new Credentials(null, victimSession, null));
      assertThat(opened.status()).as(opened.toString()).isEqualTo(200);
      String pass = JSON.readTree(opened.body()).path("accessToken").asText();
      assertThat(pass).startsWith("acct.").isNotEqualTo(victim.token());
      assertThat(call(client, "GET", V + "/cases/" + victim.id(), null, null, new Credentials(pass, victimSession, null)).status()).isEqualTo(200);
      for (var row : CASE_ENDPOINTS) {
        String session = row.path().contains("/appointment/") ? attackerSession : null;
        generic(row, "account pass without its session", refusedLikeUnknown(client, row, victim.objects(), primary(row),
            "account pass without its session", new Credentials(pass, session, null), failures), failures);
        generic(row, "account pass with another session", refusedLikeUnknown(client, row, victim.objects(), primary(row),
            "account pass with another session", new Credentials(pass, attackerSession, null), failures), failures);
      }
      for (var row : CASE_ENDPOINTS.stream().filter(row -> row.path().equals(C)).toList())
        refusedLikeUnknown(client, row, attacker.objects(), "caseId", "another case with this account pass",
            new Credentials(pass, victimSession, null), failures);
      for (var row : ACCOUNT_ENDPOINTS.stream().filter(row -> !row.path().contains("{") && !row.key().equals("POST /api/v1/account/sign-out")
          && !row.key().equals("POST /api/v1/account/deletion") && !row.key().equals("POST /api/v1/account/cases")).toList()) {
        var own = call(client, row.method(), row.path(), resolve(row.body(), row.ids(), attacker.objects(), null), UUID.randomUUID().toString(),
            new Credentials(null, attackerSession, null));
        if (own.status() >= 500 || own.body().contains(victim.id()) || own.body().contains("victim@example.com")
            || own.body().contains(victim.objects().get("journeyId")))
          failures.add(row.key() + " with another account's session: " + own);
      }

      // Operator endpoints refuse a missing, wrong or case credential before reading anything.
      for (String key : declared.entrySet().stream().filter(entry -> entry.getValue() == Scope.OPERATOR).map(Map.Entry::getKey).toList()) {
        String method = key.substring(0, key.indexOf(' ')), path = key.substring(key.indexOf(' ') + 1)
            .replace("{caseId}", victim.id()).replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        for (var bearer : Arrays.asList(null, "wrong-operator-secret", victim.token())) {
          var refused = call(client, method, path, "{}", null, new Credentials(null, null, bearer));
          if (refused.status() != 401 || !refused.body().isEmpty()) failures.add(key + " with operator token " + bearer + ": " + refused);
        }
      }
      assertThat(ServiceOperationsAcceptanceTest.opGet(client, V + "/operator/cases/" + victim.id(), SUPPORT).path("operations")).isNotEmpty();

      assertThat(comparisons).as("refusals compared with an unknown case or object").hasValueGreaterThanOrEqualTo(10 * CASE_ENDPOINTS.size());
      assertThat(failures).as("Endpoints that do not refuse like an unknown case or object").isEmpty();
    }
  }

  /** The ticket 63 Agreed Price path plus a test drive proposal, appointment, export and report, owned by one account. */
  private Fixture agreedCase(RunningApplication app, String label, String email) throws Exception {
    var client = app.client();
    var jdbc = app.context().getBean(JdbcTemplate.class);
    var created = post(client, V + "/cases", JSON.readTree(CASE_BODY), label + "-case");
    String id = created.at("/case/id").asText(), token = created.path("accessToken").asText(), base = V + "/cases/" + id;
    var objects = new HashMap<String, String>();
    objects.put("caseId", id);
    objects.put("otherCaseId", id);
    agree(app, base, id, token, label, objects);
    String document = jdbc.queryForObject("select document_id from document_processing_operations where case_id = ? order by created_at fetch first 1 rows only",
        String.class, id);
    objects.put("documentId", document);
    objects.put("operationId", jdbc.queryForObject("select id from document_processing_operations where document_id = ?", String.class, document));
    var extraction = get(client, base + "/documents/" + document + "/extractions/latest", token);
    objects.put("extractionId", extraction.path("id").asText());
    objects.put("candidateId", extraction.at("/candidates/0/id").asText());
    objects.put("conflictId", UUID.randomUUID().toString());
    objects.put("quoteId", jdbc.queryForObject("select id from quote_requests where case_id = ?", String.class, id));
    objects.put("acceptanceId", jdbc.queryForObject("select id from quote_acceptance_requests where case_id = ?", String.class, id));
    objects.put("draftId", jdbc.queryForObject("select id from outbound_drafts where case_id = ? fetch first 1 rows only", String.class, id));
    String inbound = jdbc.queryForObject("select id from dealer_relay_messages where case_id = ? and direction = 'INBOUND' fetch first 1 rows only",
        String.class, id);
    objects.put("messageId", inbound);
    var day = java.time.LocalDateTime.now(java.time.ZoneId.of("America/Chicago")).plusDays(3).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    var proposal = post(client, base + "/appointment/proposals", Map.of("timeZone", "America/Chicago",
        "windows", List.of(Map.of("start", day.withHour(10).toString(), "end", day.withHour(12).toString()))), label + "-proposal", token);
    objects.put("testDriveMessageId", proposal.path("id").asText());
    String appointment = UUID.randomUUID().toString();
    jdbc.update("""
        insert into test_drive_appointments(id, case_id, agreed_price_id, proposal_id, slot_option, starts_at, ends_at, time_zone,
          confirmation_message_id, state, created_at, scheduled_at)
        values (?, ?, (select id from agreed_prices where case_id = ?), ?, 1, ?, ?, 'America/Chicago', ?, 'SCHEDULED', current_timestamp, current_timestamp)
        """, appointment, id, id, proposal.path("id").asText(), java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(259200)),
        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(262800)), inbound);
    objects.put("appointmentId", appointment);
    String booking = UUID.randomUUID().toString();
    jdbc.update("""
        insert into test_drive_messages(id, case_id, agreed_price_id, quote_request_id, appointment_id, kind, time_zone, slots_json,
          message_text, state, idempotency_key, request_fingerprint, created_at)
        values (?, ?, (select id from agreed_prices where case_id = ?), ?, ?, 'BOOKING', 'America/Chicago', '[]', 'x', 'DRAFT', ?, 'x', current_timestamp)
        """, booking, id, id, objects.get("quoteId"), appointment, booking);
    objects.put("bookingMessageId", booking);
    objects.put("exportId", post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), label + "-export", token).path("id").asText());
    objects.put("reportTotalId", post(client, base + "/feedback/report-total", Map.of("reportVersion", 1), label + "-total", token).path("id").asText());
    objects.put("tradeOfferId", TradeVehicleAcceptanceTest.recordTradeWithOffer(app, id, token, label));

    // The owning account, with a journey, a candidate and a reference vehicle.
    post(client, V + "/account/sign-in-requests", Map.of("email", email), label + "-sign-in");
    String session = AccountSessionTestSupport.verify(client, BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1]);
    objects.put("session", session);
    client.post().uri(V + "/account/cases").header("Cookie", AccountSessionTestSupport.cookie(session)).header("Account-Request", "1")
        .header("Case-Access-Token", token).contentType(MediaType.APPLICATION_JSON).body(Map.of("caseId", id)).retrieve().toBodilessEntity();
    String account = jdbc.queryForObject("select account_id from buyer_account_cases where case_id = ?", String.class, id);
    var journey = JSON.readTree(client.post().uri(V + "/journeys").header("Cookie", AccountSessionTestSupport.cookie(session))
        .header("Account-Request", "1").header("Idempotency-Key", label + "-journey").contentType(MediaType.APPLICATION_JSON)
        .body(BuyingJourneyAcceptanceTest.brief()).retrieve().body(String.class));
    String journeyId = journey.path("id").asText();
    objects.put("journeyId", journeyId);
    String candidate = UUID.randomUUID().toString();
    jdbc.update("""
        insert into journey_candidates(id, journey_id, account_id, brief_version, search_run_id, matcher_version, candidate_json,
          listing_json, market_json, provider_version, created_at)
        values (?, ?, ?, 1, ?, 'test', '{}', '{}', '{}', 'test', current_timestamp)
        """, candidate, journeyId, account, UUID.randomUUID().toString());
    objects.put("journeyCandidateId", candidate);
    var reference = call(client, "POST", V + "/journeys/" + journeyId + "/reference-vehicles",
        "{\"basedOnVersion\":1,\"vin\":\"2T3P1RFV5MW000001\"}", null, new Credentials(null, session, null));
    assertThat(reference.status()).as(reference.toString()).isEqualTo(200);
    objects.put("referenceId", JSON.readTree(reference.body()).at("/referenceVehicles/0/referenceId").asText());
    assertThat(objects.get("referenceId")).isNotBlank();
    return new Fixture(id, token, objects);
  }

  /**
   * {@link TestDriveAcceptanceTest#agree} with a per-case inbound message, so two cases can reach an Agreed Price,
   * and a negotiation draft saved while negotiation is still active.
   */
  private static void agree(RunningApplication app, String base, String caseId, String token, String label, Map<String, String> objects)
      throws Exception {
    var client = app.client();
    var jdbc = app.context().getBean(JdbcTemplate.class);
    OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
    jdbc.update("update vehicles set vin = ? where case_id = ?", "1HGCM82633A004352", caseId);
    String dealer = UUID.randomUUID().toString();
    jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'dealer', 'North Branch Motors', current_timestamp)", dealer, caseId);
    jdbc.update("""
        insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
        values (?, ?, ?, 'listing', 'https://dealer.example/vehicle/42', '1HGCM82633A004352', 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
        """, UUID.randomUUID().toString(), caseId, dealer);
    approveMandate(client, base, token, "mandate1");
    var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote", token);
    DealerHandoffTestSupport.markSent(client, base, token,
        post(client, base + "/quote-requests/" + quote.path("id").asText() + "/approval", approval(quote), "send", token));
    String relayAddress = get(client, base + "/relay", token).path("address").asText();
    var received = app.context().getBean(com.dealanalyzer.relay.RelayService.class).receive(new com.dealanalyzer.relay.DealerMailRelay.InboundMail(
        label + "-written-quote", relayAddress, "sales@dealer.example", "Itemized quote",
        "Selling price $25,000 and OTD $26,800.\n" + "Itemized source line\n".repeat(56), List.of()));
    String documentId = jdbc.queryForObject("select document_id from dealer_relay_attachments where message_id = ?", String.class, received.messageId());
    String operation = jdbc.queryForObject("select id from document_processing_operations where document_id = ?", String.class, documentId);
    awaitExtraction(app, operation, token);
    var extraction = get(client, base + "/documents/" + documentId + "/extractions/latest", token);
    String corrections = base + "/documents/" + documentId + "/extractions/" + extraction.path("id").asText() + "/corrections";
    post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
        "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "finance", token);
    post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), "fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    var offer = post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "itemized", token);
    post(client, base + "/cash-economics", Map.of(), "cash-current", token);
    post(client, base + "/market-assessments", Map.of(), "market-current", token);
    // A target below the quote keeps negotiation active long enough to save a counteroffer draft.
    post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 2000000), "plan-low", token);
    post(client, base + "/analyses", Map.of(), label + "-analysis", token);
    approveMandate(client, base, token, "mandate-low");
    objects.put("conversationDraftId", post(client, base + "/negotiation-conversation/drafts",
        Map.of("quoteRequestId", quote.path("id").asText()), "conversation", token).path("id").asText());
    post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 3000000), "plan-current", token);
    approveMandate(client, base, token, "mandate2");
    objects.put("dealerId", dealer);
    var prepared = post(client, base + "/quote-acceptance", Map.of("quoteRequestId", quote.path("id").asText(),
        "offerVersion", offer.path("version").asInt()), "accept", token);
    DealerHandoffTestSupport.markSent(client, base, token,
        post(client, base + "/quote-acceptance/" + prepared.path("id").asText() + "/approval", approval(prepared), "accept-send", token));
  }

  private static Map<String, Object> approval(JsonNode request) {
    return Map.of("approved", true, "text", request.at("/gatedDraft/draft/text").asText());
  }

  private static void approveMandate(RestClient client, String base, String token, String key) throws Exception {
    var proposal = get(client, base + "/mandates/proposal", token);
    post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
        "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), key, token);
  }

  /** The identifier whose existence a refusal must not reveal: the case, or the operation for operation routes. */
  private static String primary(Endpoint row) {
    return row.path().startsWith(V + "/operations/") ? "operationId" : "caseId";
  }

  /** Object keys a row takes besides its case, from the path and the body. */
  private static List<String> nestedKeys(Endpoint row) {
    var keys = new LinkedHashSet<String>();
    var matcher = java.util.regex.Pattern.compile("\\{([A-Za-z]+)}").matcher(row.path() + (row.body() == null ? "" : row.body()));
    while (matcher.find()) {
      String key = row.ids().getOrDefault(matcher.group(1), matcher.group(1));
      if (!key.equals("caseId") && !CONSTANTS.containsKey(key)) keys.add(key);
    }
    return List.copyOf(keys);
  }

  private static String resolve(String template, Map<String, String> ids, Map<String, String> objects, Map<String, String> overrides) {
    if (template == null) return null;
    var matcher = java.util.regex.Pattern.compile("\\{([A-Za-z]+)}").matcher(template);
    var result = new StringBuilder();
    while (matcher.find()) {
      String key = ids.getOrDefault(matcher.group(1), matcher.group(1));
      String value = overrides != null && overrides.containsKey(key) ? overrides.get(key)
          : CONSTANTS.containsKey(key) ? CONSTANTS.get(key) : objects.get(key);
      if (value == null) throw new AssertionError("No fixture value for " + key + " in " + template);
      matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Calls the row as given and again with {@code key} replaced by an identifier nothing has; the refusal must
   * not succeed and must match the unknown one in status, headers and body.
   */
  private static Response refusedLikeUnknown(RestClient client, Endpoint row, Map<String, String> objects, String key, String label,
      Credentials credentials, List<String> failures) {
    String real = objects.get(key);
    String unknown = UUID.randomUUID().toString();
    var actual = call(client, row.method(), resolve(row.path(), row.ids(), objects, null), resolve(row.body(), row.ids(), objects, null),
        UUID.randomUUID().toString(), credentials);
    var baseline = call(client, row.method(), resolve(row.path(), row.ids(), objects, Map.of(key, unknown)),
        resolve(row.body(), row.ids(), objects, Map.of(key, unknown)), UUID.randomUUID().toString(), credentials);
    comparisons.incrementAndGet();
    String where = row.key() + " [" + label + "]";
    if (actual.status() < 400 || actual.status() >= 500) failures.add(where + " was not refused: " + actual);
    else if (!normalized(actual, real).equals(normalized(baseline, unknown)))
      failures.add(where + " differs from an unknown " + key + ":\n    real    " + actual + "\n    unknown " + baseline);
    return actual;
  }

  /**
   * A presented but unusable case credential gets the generic 404, so the refusal comes from the access check
   * rather than from validation that ran first. Booking first requires the Buyer Account that holds the case,
   * which refuses an attacker's session with the same 403 for every case.
   */
  private static void generic(Endpoint row, String label, Response refusal, List<String> failures) {
    int expected = row.key().equals("POST " + C + "/appointment/bookings") ? 403 : 404;
    if (refusal.status() != expected) failures.add(row.key() + " [" + label + "] was refused with " + refusal.status() + ", not " + expected);
  }

  private static Response normalized(Response response, String id) {
    var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
    // The date and correlation ID differ on every request, and Tomcat closes a connection after its 100th request.
    var transport = Set.of("date", "x-correlation-id", "connection", "keep-alive");
    response.headers().forEach((name, values) -> { if (!transport.contains(name.toLowerCase(Locale.ROOT))) headers.put(name, values); });
    return new Response(response.status(), headers, response.body().replace(id, "<id>"));
  }

  private static Response call(RestClient client, String method, String path, String body, String idempotencyKey, Credentials credentials) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path);
    if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
    if (credentials.caseToken() != null) request.header("Case-Access-Token", credentials.caseToken());
    if (credentials.session() != null) request.header("Cookie", AccountSessionTestSupport.cookie(credentials.session()));
    if (credentials.bearer() != null) request.header("Authorization", "Bearer " + credentials.bearer());
    if (!method.equals("GET")) request.header("Account-Request", "1");
    if (MULTIPART.equals(body)) {
      var parts = new LinkedMultiValueMap<String, Object>();
      parts.add("file", new NamedBytes("quote.pdf", "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
      request.contentType(MediaType.MULTIPART_FORM_DATA).body(parts);
    } else if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      var headers = new LinkedHashMap<String, List<String>>();
      response.getHeaders().forEach(headers::put);
      return new Response(response.getStatusCode().value(), headers,
          new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    });
  }

  /** Every registered mapping as "METHOD path", with whether it takes the case credential or a {caseId}. */
  private static Map<String, Boolean> inventory(RunningApplication app) {
    var result = new TreeMap<String, Boolean>();
    app.context().getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods()
        .forEach((mapping, method) -> {
          boolean caseToken = Arrays.stream(method.getMethodParameters()).anyMatch(parameter -> {
            var header = parameter.getParameterAnnotation(RequestHeader.class);
            return header != null && ("Case-Access-Token".equals(header.value()) || "Case-Access-Token".equals(header.name()));
          });
          var methods = mapping.getMethodsCondition().getMethods();
          for (String path : mapping.getPatternValues()) {
            boolean scoped = caseToken || path.contains("{caseId}") && !path.startsWith(V + "/operator/");
            if (methods.isEmpty()) result.put("* " + path, scoped);
            else methods.forEach(verb -> result.put(verb.name() + " " + path, scoped));
          }
        });
    return result;
  }
}
