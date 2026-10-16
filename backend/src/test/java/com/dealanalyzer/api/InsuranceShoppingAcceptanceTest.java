package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.Result;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.call;
import static com.dealanalyzer.api.TradeVehicleAcceptanceTest.put;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.insurance.InsuranceShoppingService;
import com.dealanalyzer.insurance.quote.DeterministicFakeInsuranceQuoteAdapter;
import com.dealanalyzer.insurance.quote.InsuranceApplication;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter;
import com.dealanalyzer.insurance.quote.InsuranceQuoteConsent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Ticket 126: one insurance application, the partner's consent, and quotes compared at the same coverage (INS-01 to INS-04). */
@ExtendWith(OutputCaptureExtension.class)
class InsuranceShoppingAcceptanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String VIN = "1HGCM82633A004352";
  static final String ZIP = "33647";
  static final String CURRENT_INSURER = "Gulfside Mutual Assurance";
  static final String OWN_INSURER = "Panhandle Farmers Insurance Exchange";
  @TempDir Path directory;

  /** Every application the partner adapter received, with the consent it came with. */
  static final CopyOnWriteArrayList<Object[]> sent = new CopyOnWriteArrayList<>();

  @TestConfiguration @Import(ShortlistAcceptanceTest.Configuration.class)
  static class Configuration {
    @Bean @Primary InsuranceQuoteAdapter recordingInsurancePartner() {
      var fake = new DeterministicFakeInsuranceQuoteAdapter();
      return new InsuranceQuoteAdapter() {
        @Override public Provider provider() { return fake.provider(); }
        @Override public Result request(InsuranceApplication application, InsuranceQuoteConsent consent) {
          sent.add(new Object[] {application, consent});
          return fake.request(application, consent);
        }
      };
    }
  }

  @BeforeEach void clear() { sent.clear(); ShortlistAcceptanceTest.links.clear(); }

  @Test void aCaseApplicationNeedsConsentGoesOnlyToThePartnerAndComparesQuotesAtTheChosenCoverage(CapturedOutput output) throws Exception {
    try (var app = start("case-insurance")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id + "/insurance";

      var empty = call(client, "GET", base, null, token, null).body();
      assertThat(empty.path("holder").asText()).isEqualTo("CASE");
      assertThat(empty.path("application").isNull()).isTrue();
      assertThat(empty.path("results").isNull()).isTrue();
      assertThat(empty.at("/consent/version").asText()).isEqualTo(InsuranceShoppingService.CONSENT_VERSION);
      assertThat(empty.at("/consent/text/0").asText()).contains("goes to no one else");
      assertThat(empty.path("coverageGuide").findValuesAsText("explanation")).hasSize(6).allMatch(text -> text.length() > 40);

      // Field errors name the correction and never echo the input; nothing is stored.
      var invalid = call(client, "PUT", base, Map.of("drivers", List.of(Map.of("ageYears", 12, "licenceState", "Florida",
          "yearsLicensed", 30, "atFaultAccidents", 0, "violations", 0)), "garaging", Map.of("zip", "3364", "state", "FL"),
          "vehicle", Map.of("vin", "1HGCM82633A004353", "modelYear", 2023, "make", "Honda", "model", "Accord", "usage", "RACING"),
          "coverage", List.of(Map.of("type", "COLLISION", "perAccidentLimitMinor", 5000000))), token, null);
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().path("code").asText()).isEqualTo("INSURANCE_INVALID");
      assertThat(invalid.body().path("errors").findValuesAsText("field")).containsExactly("drivers[0].ageYears",
          "drivers[0].licenceState", "drivers[0].yearsLicensed", "garaging.zip", "vehicle.vin", "vehicle.usage", "coverage.COLLISION");
      assertThat(invalid.body().toString()).doesNotContain("3364", "1HGCM82633A004353", "RACING", "Florida");
      assertThat(jdbc(app).queryForObject("select count(*) from insurance_applications", Long.class)).isZero();

      var stored = put(client, base, application(null, 91000L), token);
      assertThat(stored.path("revision").asInt()).isEqualTo(1);
      assertThat(stored.at("/application/vehicle/vin").asText()).isEqualTo(VIN);
      assertThat(stored.at("/application/coverage").findValuesAsText("type"))
          .containsExactly("BODILY_INJURY_LIABILITY", "PROPERTY_DAMAGE_LIABILITY", "PERSONAL_INJURY_PROTECTION", "COLLISION");
      // Sending the same details again changes nothing; an edit of an older revision is refused.
      assertThat(put(client, base, application(null, 91000L), token).path("revision").asInt()).isEqualTo(1);
      var conflict = call(client, "PUT", base, application(null, 95000L), token, null);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.body().path("code").asText()).isEqualTo("INSURANCE_APPLICATION_CHANGED");

      // Without the Buyer's agreement to the consent shown now, nothing is sent and nothing is metered (INS-02).
      for (var refused : List.<Map<String, Object>>of(Map.of(), Map.of("consentTextVersion", InsuranceShoppingService.CONSENT_VERSION),
          Map.of("consentTextVersion", "an-older-consent", "agreed", true))) {
        var result = keyed(client, "POST", base + "/quote-requests", refused, token, null, "refused-" + refused.size());
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().path("code").asText()).isEqualTo("INSURANCE_CONSENT_REQUIRED");
      }
      assertThat(sent).isEmpty();
      assertThat(jdbc(app).queryForObject("select count(*) from provider_usage where operation = 'INSURANCE_QUOTE'", Long.class)).isZero();

      var consent = Map.of("consentTextVersion", InsuranceShoppingService.CONSENT_VERSION, "agreed", true);
      var requested = keyed(client, "POST", base + "/quote-requests", consent, token, null, "first-request");
      assertThat(requested.status()).isEqualTo(201);
      var results = requested.body().path("results");
      // The adapter received the application once, with the consent version, and nothing that names a person.
      assertThat(sent).hasSize(1);
      var application = (InsuranceApplication) sent.getFirst()[0];
      assertThat(((InsuranceQuoteConsent) sent.getFirst()[1]).consentTextVersion()).isEqualTo(InsuranceShoppingService.CONSENT_VERSION);
      assertThat(application.drivers()).singleElement().satisfies(driver -> {
        assertThat(driver.ageYears()).isEqualTo(41); assertThat(driver.licenceState()).isEqualTo("FL");
      });
      assertThat(application.garaging().zip()).isEqualTo(ZIP);
      assertThat(application.vehicle().usage()).isEqualTo(InsuranceApplication.Usage.COMMUTE);
      assertThat(application.currentPolicy().insurer()).isEqualTo(CURRENT_INSURER);
      // Nothing else carried it: no dealer message, no email, and the usage row holds no application detail.
      assertThat(jdbc(app).queryForObject("select count(*) from outbound_drafts", Long.class)).isZero();
      assertThat(ShortlistAcceptanceTest.links).isEmpty();
      var usage = jdbc(app).queryForMap("select * from provider_usage where case_id = ? and operation = 'INSURANCE_QUOTE'", id);
      assertThat(usage.values()).noneMatch(value -> value != null && List.of(ZIP, VIN, CURRENT_INSURER).stream().anyMatch(value.toString()::contains));
      var request = jdbc(app).queryForMap("select * from insurance_quote_requests");
      assertThat(request.get("consent_text_version")).isEqualTo(InsuranceShoppingService.CONSENT_VERSION);
      assertThat(request.get("consent_agreed_at")).isNotNull();

      // Same-coverage quotes are ordered by yearly premium, whatever the term (INS-03).
      var same = results.path("sameCoverage");
      assertThat(same.findValuesAsText("insurer"))
          .containsExactly("Synthetic Insurer Alpha", "Synthetic Insurer Beta", "Synthetic Insurer Delta");
      var yearly = new ArrayList<Long>();
      same.forEach(quote -> yearly.add(quote.path("yearlyPremiumMinor").asLong()));
      assertThat(yearly).isSorted();
      var beta = same.get(1);
      assertThat(beta.path("term").asText()).isEqualTo("SIX_MONTHS");
      assertThat(beta.path("yearlyPremiumMinor").asLong()).isEqualTo(2 * beta.path("premiumMinor").asLong());
      assertThat(beta.path("continueUrl").asText()).startsWith("https://");
      assertThat(beta.path("expiresAt").asText()).isNotBlank();
      // Each same-coverage quote shows its yearly difference from the current premium, as arithmetic (INS-04).
      for (var quote : same) {
        var difference = quote.path("yearlyDifference");
        assertThat(difference.path("currentYearlyMinor").asLong()).isEqualTo(182000);
        assertThat(difference.path("quoteMinusCurrentMinor").asLong()).isEqualTo(quote.path("yearlyPremiumMinor").asLong() - 182000);
        assertThat(difference.path("currentCoverage").asText()).isEqualTo("SAME_AS_CHOSEN");
      }
      assertThat(results.path("currentPremiumEntered").asBoolean()).isTrue();
      // Different coverage is labelled with its differences and never ranked; declines and timeouts are stated.
      var gamma = results.at("/differentCoverage/0");
      assertThat(gamma.path("insurer").asText()).isEqualTo("Synthetic Insurer Gamma");
      assertThat(gamma.path("yearlyDifference").isNull()).isTrue();
      assertThat(gamma.path("differences").findValuesAsText("aspect")).containsOnly("DEDUCTIBLE");
      assertThat(results.path("withoutQuote").findValuesAsText("outcome")).containsExactly("TIMED_OUT");
      assertThat(results.at("/request/notice").asText()).contains("Synthetic");
      assertThat(results.at("/request/outdated").asBoolean()).isFalse();

      // A retried request sends nothing more.
      assertThat(keyed(client, "POST", base + "/quote-requests", consent, token, null, "first-request").status()).isEqualTo(201);
      assertThat(sent).hasSize(1);

      // A Buyer-entered quote from a non-participating insurer is compared on the same terms (PAY-02).
      var own = keyed(client, "POST", base + "/own-quotes", Map.of("insurer", OWN_INSURER, "premiumMinor", 190000,
          "term", "TWELVE_MONTHS", "sameCoverage", true), token, null, "own-same");
      assertThat(own.status()).isEqualTo(201);
      var withOwn = own.body().path("results");
      assertThat(withOwn.at("/sameCoverage/0/insurer").asText()).isEqualTo(OWN_INSURER);
      assertThat(withOwn.at("/sameCoverage/0/source").asText()).isEqualTo("BUYER_ENTERED");
      assertThat(withOwn.at("/sameCoverage/0/continueUrl").isNull()).isTrue();
      assertThat(withOwn.at("/sameCoverage/0/yearlyDifference/quoteMinusCurrentMinor").asLong()).isEqualTo(8000);
      assertThat(keyed(client, "POST", base + "/own-quotes", Map.of("insurer", OWN_INSURER, "premiumMinor", 190000,
          "term", "TWELVE_MONTHS"), token, null, "own-same").body().at("/results/sameCoverage")).as("a retry adds nothing").hasSize(4);
      var differentOwn = keyed(client, "POST", base + "/own-quotes", Map.of("insurer", "Suncoast Direct", "premiumMinor", 99000,
          "term", "SIX_MONTHS", "sameCoverage", false, "coverage", List.of(
              Map.of("type", "PROPERTY_DAMAGE_LIABILITY", "perAccidentLimitMinor", 1000000),
              Map.of("type", "PERSONAL_INJURY_PROTECTION", "perPersonLimitMinor", 1000000, "deductibleMinor", 0))),
          token, null, "own-different").body().path("results");
      assertThat(differentOwn.path("differentCoverage").findValuesAsText("insurer")).containsExactly("Suncoast Direct", "Synthetic Insurer Gamma");
      assertThat(differentOwn.at("/differentCoverage/0/differences").findValuesAsText("aspect"))
          .contains("NOT_IN_QUOTE", "PER_ACCIDENT_LIMIT");
      LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
      var expired = keyed(client, "POST", base + "/own-quotes", Map.of("insurer", "Old Renewal Notice", "premiumMinor", 150000,
          "term", "TWELVE_MONTHS", "quotedOn", today.minusDays(40).toString(), "expiresOn", today.minusDays(1).toString()),
          token, null, "own-expired").body().path("results");
      assertThat(expired.path("expired").findValuesAsText("insurer")).containsExactly("Old Renewal Notice");
      assertThat(expired.path("sameCoverage").findValuesAsText("insurer")).doesNotContain("Old Renewal Notice");
      var refusedOwn = keyed(client, "POST", base + "/own-quotes", Map.of("insurer", " ", "premiumMinor", -5, "term", "WEEKLY",
          "quotedOn", today.plusDays(3).toString()), token, null, "own-bad");
      assertThat(refusedOwn.status()).isEqualTo(400);
      assertThat(refusedOwn.body().path("errors").findValuesAsText("field")).containsExactly("insurer", "premiumMinor", "term", "quotedOn");
      String ownId = withOwn.at("/sameCoverage/0/id").asText();
      String partnerId = withOwn.at("/sameCoverage/1/id").asText();
      assertThat(call(client, "DELETE", base + "/own-quotes/" + partnerId, null, token, null).status()).as("a partner quote stays").isEqualTo(404);
      var removed = call(client, "DELETE", base + "/own-quotes/" + ownId, null, token, null).body();
      assertThat(removed.at("/results/sameCoverage").findValuesAsText("insurer")).doesNotContain(OWN_INSURER);

      // A changed application marks the quotes as requested for an earlier version.
      var changed = put(client, base, application(1, 95000L), token);
      assertThat(changed.at("/results/request/outdated").asBoolean()).isTrue();

      // A malformed request is refused without quoting it.
      var unreadable = client.put().uri(base).header("Case-Access-Token", token).contentType(MediaType.APPLICATION_JSON)
          .body("{\"drivers\":[{\"ageYears\":\"forty-one " + ZIP + "\"}]}")
          .exchange((ignored, response) -> new Result(response.getStatusCode().value(), JSON.readTree(response.getBody())));
      assertThat(unreadable.status()).isEqualTo(400);
      assertThat(unreadable.body().path("code").asText()).isEqualTo("INSURANCE_UNREADABLE");
      assertThat(unreadable.body().toString()).doesNotContain(ZIP);
      // Another case's credential reads nothing.
      var other = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "other");
      assertThat(call(client, "GET", base, null, other.path("accessToken").asText(), null).status()).isEqualTo(404);

      // The case export holds the application, the consent-backed request and the quotes; case deletion removes them.
      var job = post(client, "/api/v1/cases/" + id + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] archive = client.get().uri("/api/v1/cases/" + id + "/exports/" + job.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      var data = JSON.readTree(zipEntries(archive).get("case-data.json"));
      assertThat(data.at("/insurance_applications/0/application_json/garaging/zip").asText()).isEqualTo(ZIP);
      assertThat(data.at("/insurance_quote_requests/0/consent_text_version").asText()).isEqualTo(InsuranceShoppingService.CONSENT_VERSION);
      assertThat(data.path("insurance_quotes").findValuesAsText("insurer")).contains("Synthetic Insurer Alpha", "Suncoast Direct");
      assertThat(data.toString()).doesNotContain("idempotency");
      post(client, "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      for (String table : List.of("insurance_applications", "insurance_quote_requests", "insurance_quotes"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();

      // No application field reached the logs.
      assertThat(output.getAll()).doesNotContain(ZIP, VIN, CURRENT_INSURER, OWN_INSURER, "Suncoast Direct", "forty-one");
    }
  }

  @Test void aJourneyHoldsOneApplicationWithOrWithoutACaseAndItIsExportedAndDeletedWithTheJourney(CapturedOutput output) throws Exception {
    try (var app = start("journey-insurance")) {
      var client = app.client();
      String session = signIn(client, "insured@example.com");
      String journeyId = account(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, "journey")
          .body().path("id").asText();
      String base = "/api/v1/journeys/" + journeyId + "/insurance";

      // On its own: the journey holds the application without any case.
      assertThat(account(client, "GET", base, null, session, null).body().path("holder").asText()).isEqualTo("JOURNEY");
      var stored = account(client, "PUT", base, application(null, null), session, null);
      assertThat(stored.status()).isEqualTo(200);
      assertThat(stored.body().path("caseVehicle").isNull()).isTrue();
      var requested = account(client, "POST", base + "/quote-requests", Map.of("consentTextVersion",
          InsuranceShoppingService.CONSENT_VERSION, "agreed", true), session, "journey-request");
      assertThat(requested.status()).isEqualTo(201);
      assertThat(requested.body().at("/results/sameCoverage")).hasSize(3);
      // Without a current premium there is no yearly difference.
      assertThat(requested.body().at("/results/sameCoverage/0/yearlyDifference").isNull()).isTrue();
      assertThat(requested.body().at("/results/currentPremiumEntered").asBoolean()).isFalse();
      assertThat(jdbc(app).queryForObject("select count(*) from provider_usage where journey_id = ? and operation = 'INSURANCE_QUOTE'",
          Long.class, journeyId)).isEqualTo(1);
      // Writes need the account request marker.
      int unmarked = client.put().uri(base).header("Cookie", AccountSessionTestSupport.cookie(session)).contentType(MediaType.APPLICATION_JSON)
          .body(application(1, null)).exchange((ignored, response) -> response.getStatusCode().value());
      assertThat(unmarked).isEqualTo(403);

      // A case opened from the journey uses the journey's one application (INS-01).
      var candidates = account(client, "POST", "/api/v1/journeys/" + journeyId + "/candidates", null, session, null).body().path("candidates");
      var opened = account(client, "POST", "/api/v1/journeys/" + journeyId + "/candidates/"
          + candidates.get(0).path("candidateId").asText() + "/case", CasePortabilityAcceptanceTest.buyingCase(), session, "case").body();
      String caseId = opened.at("/case/id").asText(), token = opened.path("accessToken").asText();
      var fromCase = call(client, "GET", "/api/v1/cases/" + caseId + "/insurance", null, token, null).body();
      assertThat(fromCase.path("holder").asText()).isEqualTo("JOURNEY");
      assertThat(fromCase.path("revision").asInt()).isEqualTo(1);
      assertThat(fromCase.at("/results/sameCoverage")).hasSize(3);
      keyed(client, "POST", "/api/v1/cases/" + caseId + "/insurance/own-quotes", Map.of("insurer", OWN_INSURER, "premiumMinor", 210000,
          "term", "TWELVE_MONTHS"), token, null, "journey-own");
      assertThat(account(client, "GET", base, null, session, null).body().at("/results/sameCoverage")).hasSize(4);

      // The account export holds the journey's application, request and quotes.
      byte[] archive = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session))
          .header("Account-Request", "1").retrieve().body(byte[].class);
      var exported = JSON.readTree(zipEntries(archive).get("account.json"));
      var insurance = exported.at("/buyingJourneys/0/insuranceApplication");
      assertThat(insurance.at("/application/garaging/zip").asText()).isEqualTo(ZIP);
      assertThat(insurance.path("quoteRequests")).hasSize(1);
      assertThat(insurance.path("quotes").findValuesAsText("insurer")).contains("Synthetic Insurer Alpha", OWN_INSURER);
      // Partner quotes from one request share a created_at, so export order among them is not fixed; find an offer.
      var offered = new ArrayList<JsonNode>();
      insurance.path("quotes").forEach(quote -> { if ("OFFERED".equals(quote.path("outcome").asText())) offered.add(quote); });
      assertThat(offered).isNotEmpty().allSatisfy(quote -> assertThat(quote.path("coverage").isArray()).isTrue());
      assertThat(exported.path("journeyInsuranceQuoteRequests")).hasSize(1);

      // Deleting the journey removes the application, its requests and quotes.
      assertThat(account(client, "DELETE", "/api/v1/journeys/" + journeyId, null, session, null).status()).isEqualTo(200);
      for (String table : List.of("insurance_applications", "insurance_quote_requests", "insurance_quotes"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
      assertThat(output.getAll()).doesNotContain(ZIP, VIN, OWN_INSURER);
    }
  }

  @Test void accountDeletionRemovesTheJourneyApplication() throws Exception {
    try (var app = start("account-insurance")) {
      var client = app.client();
      String session = signIn(client, "leaving-insured@example.com");
      String journeyId = account(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, "journey")
          .body().path("id").asText();
      String base = "/api/v1/journeys/" + journeyId + "/insurance";
      account(client, "PUT", base, application(null, 91000L), session, null);
      account(client, "POST", base + "/quote-requests", Map.of("consentTextVersion", InsuranceShoppingService.CONSENT_VERSION,
          "agreed", true), session, "request");
      account(client, "POST", base + "/own-quotes", Map.of("insurer", OWN_INSURER, "premiumMinor", 190000, "term", "TWELVE_MONTHS"),
          session, "own");
      assertThat(account(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      for (String table : List.of("insurance_applications", "insurance_quote_requests", "insurance_quotes"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
  }

  static Map<String, Object> application(Integer revision, Long currentPremiumMinor) {
    var input = new LinkedHashMap<String, Object>();
    if (revision != null) input.put("basedOnRevision", revision);
    input.put("drivers", List.of(Map.of("ageYears", 41, "licenceState", "fl", "yearsLicensed", 22, "atFaultAccidents", 0, "violations", 0)));
    input.put("garaging", Map.of("zip", ZIP, "state", "FL"));
    input.put("vehicle", Map.of("vin", VIN, "modelYear", 2023, "make", "Honda", "model", "Accord", "trim", "EX", "usage", "COMMUTE",
        "annualMileage", 12000));
    input.put("coverage", List.of(
        Map.of("type", "COLLISION", "deductibleMinor", 50000),
        Map.of("type", "BODILY_INJURY_LIABILITY", "perPersonLimitMinor", 10000000, "perAccidentLimitMinor", 30000000),
        Map.of("type", "PROPERTY_DAMAGE_LIABILITY", "perAccidentLimitMinor", 5000000),
        Map.of("type", "PERSONAL_INJURY_PROTECTION", "perPersonLimitMinor", 1000000, "deductibleMinor", 0)));
    if (currentPremiumMinor != null)
      input.put("currentPolicy", Map.of("insurer", CURRENT_INSURER, "premiumMinor", currentPremiumMinor, "term", "SIX_MONTHS",
          "coverage", "SAME_AS_CHOSEN"));
    return input;
  }

  private RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, false, Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--case.deletion.delay=PT1H");
  }

  private static Result keyed(RestClient client, String method, String path, Object body, String token, String sourceToken, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Case-Access-Token", token).header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static JdbcTemplate jdbc(RunningApplication app) {
    return new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    String session = AccountSessionTestSupport.verify(client, ShortlistAcceptanceTest.links.getLast().split("#sign-in=")[1]);
    ShortlistAcceptanceTest.links.clear();
    return session;
  }

  private static Result account(RestClient client, String method, String path, Object body, String session, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Cookie", AccountSessionTestSupport.cookie(session));
    if (!"GET".equals(method)) request.header("Account-Request", "1");
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static Map<String, String> zipEntries(byte[] archive) throws Exception {
    var result = new HashMap<String, String>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
        if (entry.getName().endsWith(".json")) result.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    return result;
  }
}
