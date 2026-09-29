package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Ticket 23. A Buying Case starts from Buyer-supplied locations, and a location the service cannot
 * calculate for is refused before anything is written.
 *
 * <p>The postal codes are real values from the Census dataset in V22: 27601 and 27604 sit inside
 * the one configured jurisdiction, 73960 is a genuine Texas ZIP with no policy pack, 73301 is a
 * PO-box-only ZIP that has no ZCTA at all, and 27048 straddles the North Carolina/Virginia line.
 */
class BuyingCaseIntakeAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;
  private HttpAcceptanceHarness harness;

  @BeforeEach
  void createHttpHarness() {
    harness = new HttpAcceptanceHarness(temporaryDirectory);
  }

  @Test
  void theBuyerJurisdictionIsDerivedFromTheEnteredPostalCodeAndConsentIsRecorded() throws Exception {
    try (RunningApplication application = harness.start("intake-derives-jurisdiction")) {
      JsonNode created = create(application.client(), "derive", intake("27601", "27604", null));

      assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
      assertThat(created.at("/case/dealerLocation").asText()).isEqualTo("27604");
      assertThat(created.at("/case/status").asText()).isEqualTo("DRAFT");
      assertThat(created.get("accessToken").asText()).isNotBlank();

      JsonNode consents = created.at("/case/consents");
      assertThat(consents).isNotEmpty();
      assertThat(consents.findValuesAsText("purpose")).contains("SERVICE_PROCESSING");
    }
  }

  @Test
  void aRealJurisdictionWithoutAPolicyPackIsRefusedAndNothingIsStored() throws Exception {
    try (RunningApplication application = harness.start("intake-refuses-unsupported")) {
      Captured refused = capture(application.client(), "shared-key", intake("73960", "27604", null));

      assertThat(refused.status()).isEqualTo(422);
      JsonNode problem = JSON.readTree(refused.body());
      assertThat(problem.get("reason").asText()).isEqualTo("JURISDICTION_NOT_SUPPORTED");
      assertThat(problem.get("field").asText()).isEqualTo("buyerPostalCode");
      assertThat(problem.get("candidateJurisdictions").findValuesAsText(""))
          .isEmpty();  // candidates are listed as plain strings, checked below
      assertThat(problem.get("candidateJurisdictions").toString()).contains("US-NC");
      assertThat(problem.get("detail").asText()).doesNotContain("73960");

      // A refused location stored nothing, so the same Idempotency-Key is still free to use.
      JsonNode created = create(application.client(), "shared-key", intake("27601", "27604", null));
      assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
    }
  }

  @Test
  void anUnrecognizedPostalCodeIsReportedSeparatelyFromAnUnsupportedJurisdiction() throws Exception {
    try (RunningApplication application = harness.start("intake-unrecognized")) {
      Captured refused = capture(application.client(), "unrecognized", intake("73301", "27604", null));

      assertThat(refused.status()).isEqualTo(422);
      JsonNode problem = JSON.readTree(refused.body());
      assertThat(problem.get("reason").asText()).isEqualTo("UNRECOGNIZED_POSTAL_CODE");
      assertThat(problem.get("field").asText()).isEqualTo("buyerPostalCode");
      assertThat(problem.get("detail").asText()).doesNotContain("73301");
    }
  }

  @Test
  void aPostalCodeCrossingAStateLineIsNotGuessedForTheBuyer() throws Exception {
    try (RunningApplication application = harness.start("intake-crossing")) {
      Captured unstated = capture(application.client(), "crossing", intake("27048", "27604", null));

      assertThat(unstated.status()).isEqualTo(422);
      JsonNode problem = JSON.readTree(unstated.body());
      assertThat(problem.get("reason").asText()).isEqualTo("POSTAL_CODE_CROSSES_STATE_LINE");
      assertThat(problem.get("field").asText()).isEqualTo("buyerJurisdiction");
      assertThat(problem.get("candidateJurisdictions").toString()).contains("US-NC", "US-VA");

      Captured foreign =
          capture(application.client(), "crossing-wrong", intake("27048", "27604", "US-SC"));
      assertThat(foreign.status()).isEqualTo(400);
      assertThat(JSON.readTree(foreign.body()).get("field").asText()).isEqualTo("buyerJurisdiction");

      JsonNode stated = create(application.client(), "crossing-stated", intake("27048", "27604", "US-NC"));
      assertThat(stated.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
    }
  }

  @Test
  void aStatedJurisdictionCannotOverrideAnUnambiguousPostalCode() throws Exception {
    try (RunningApplication application = harness.start("intake-no-override")) {
      Captured refused = capture(application.client(), "override", intake("73960", "27604", "US-NC"));

      assertThat(refused.status()).isEqualTo(400);
      JsonNode problem = JSON.readTree(refused.body());
      assertThat(problem.get("field").asText()).isEqualTo("buyerJurisdiction");
    }
  }

  @Test
  void malformedPostalCodesNameTheFieldAndTheRequiredCorrection() throws Exception {
    try (RunningApplication application = harness.start("intake-malformed")) {
      Captured buyer = capture(application.client(), "short-buyer", intake("2760", "27604", null));
      assertThat(buyer.status()).isEqualTo(400);
      JsonNode problem = JSON.readTree(buyer.body());
      assertThat(problem.get("field").asText()).isEqualTo("buyerPostalCode");
      assertThat(problem.get("detail").asText()).isEqualTo("Enter a five-digit United States ZIP code.");
      assertThat(problem.get("detail").asText()).doesNotContain("2760");

      Captured dealer = capture(application.client(), "letters-dealer", intake("27601", "RALEI", null));
      assertThat(dealer.status()).isEqualTo(400);
      assertThat(JSON.readTree(dealer.body()).get("field").asText()).isEqualTo("dealerPostalCode");
    }
  }

  @Test
  void theSupportedJurisdictionsAreReadableSoIntakeNeedNotHardCodeThem() throws Exception {
    try (RunningApplication application = harness.start("intake-jurisdictions")) {
      JsonNode view = application.client().get()
          .uri("/api/v1/intake-options")
          .retrieve()
          .body(JsonNode.class);

      assertThat(view.get("supported").toString()).isEqualTo("[\"US-NC\"]");
      assertThat(view.at("/consentPolicies/serviceProcessing").asText())
          .isEqualTo("service-processing.v1");
    }
  }

  @Test
  void syntheticIntakeIsUnreachableWhenTheAffordanceIsOff() throws Exception {
    try (RunningApplication application =
        harness.start("intake-synthetic-off", true, "--intake.synthetic.enabled=false")) {
      Captured refused = application.client().post()
          .uri("/api/v1/cases/synthetic")
          .contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "synthetic-off")
          .body(Map.of("buyerZip", "27601", "dealerZip", "27604"))
          .exchange((sent, response) -> new Captured(
              response.getStatusCode().value(), new String(response.getBody().readAllBytes())));

      // GET /cases/{caseId} already claims this path, so an unregistered POST surfaces as
      // method-not-allowed. What matters is that nothing accepts the fixture and starts an analysis.
      assertThat(refused.status()).isEqualTo(405);
      assertThat(refused.body()).doesNotContain("operation");
    }
  }

  private static Map<String, Object> intake(
      String buyerPostalCode, String dealerPostalCode, String statedJurisdiction) {
    Map<String, Object> request = new HashMap<>();
    request.put("buyerPostalCode", buyerPostalCode);
    request.put("dealerPostalCode", dealerPostalCode);
    request.put("purchaseMethod", "CASH");
    request.put("consents", Map.of(
        "serviceProcessing", Map.of("granted", true, "policyVersion", "service.v1"),
        "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
        "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1")));
    if (statedJurisdiction != null) {
      request.put("buyerJurisdiction", statedJurisdiction);
    }
    return request;
  }

  private static JsonNode create(RestClient client, String idempotencyKey, Object request)
      throws Exception {
    return JSON.readTree(client.post()
        .uri("/api/v1/cases")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(request)
        .retrieve()
        .body(String.class));
  }

  private static Captured capture(RestClient client, String idempotencyKey, Object request) {
    return client.post()
        .uri("/api/v1/cases")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(request)
        .exchange((sent, response) -> new Captured(
            response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
  }

  private record Captured(int status, String body) {}
}
