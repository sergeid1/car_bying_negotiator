package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class EphemeralBuyingCaseAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;
  private HttpAcceptanceHarness harness;

  @BeforeEach
  void createHttpHarness() {
    harness = new HttpAcceptanceHarness(temporaryDirectory);
  }

  @Test
  void buyerCreatesAndSecurelyReopensAnAccountFreeBuyingCase() throws Exception {
    try (RunningApplication application = harness.start("secure-reopen")) {
      JsonNode created = createCase(application.client(), "create-secure-case", validCase());

      assertThat(created.get("accessToken").asText()).isNotBlank();
      assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
      assertThat(created.at("/case/dealerLocation").asText()).isEqualTo("27604");
      assertThat(created.at("/case/purchaseMethod").asText()).isEqualTo("CASH");
      assertThat(created.at("/case/status").asText()).isEqualTo("DRAFT");
      assertThat(created.at("/case/buyerLimits/maxSellingPriceMinor").asLong())
          .isEqualTo(25_000_00L);

      JsonNode reopened = application.client().get()
          .uri("/api/v1/cases/{caseId}", created.at("/case/id").asText())
          .header("Case-Access-Token", created.get("accessToken").asText())
          .retrieve()
          .body(JsonNode.class);

      assertThat(reopened).isEqualTo(created.get("case"));
      assertThat(reopened.has("accessToken")).isFalse();
    }
  }

  @Test
  void invalidCaseCredentialsRevealNoBuyingCaseData() throws Exception {
    try (RunningApplication application = harness.start("invalid-credentials")) {
      JsonNode created = createCase(application.client(), "private-case", validCase());
      String caseId = created.at("/case/id").asText();

      List<CapturedResponse> responses = new ArrayList<>();
      application.client().get()
          .uri("/api/v1/cases/{caseId}", caseId)
          .exchange((request, response) -> {
            responses.add(new CapturedResponse(
                response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
            return null;
          });
      application.client().get()
          .uri("/api/v1/cases/{caseId}", caseId)
          .header("Case-Access-Token", "not-the-secret")
          .exchange((request, response) -> {
            responses.add(new CapturedResponse(
                response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
            return null;
          });

      assertThat(responses).allSatisfy(response -> {
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("US-NC", "27604", "2500000");
      });
    }
  }

  @Test
  void serviceProcessingConsentIsRequiredButSecondaryConsentIsOptional() throws Exception {
    try (RunningApplication application = harness.start("required-service-consent")) {
      Map<String, Object> declinedService = Map.of(
          "buyerJurisdiction", "US-NC",
          "dealerLocation", "27604",
          "purchaseMethod", "FINANCE",
          "consents", Map.of(
              "serviceProcessing", Map.of("granted", false, "policyVersion", "service.v1"),
              "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
              "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1")));

      CapturedResponse declined = captureCreate(
          application.client(), "service-consent-key", declinedService);
      assertThat(declined.status()).isEqualTo(400);

      JsonNode created = createCase(
          application.client(), "service-consent-key", validCaseWithoutLimits("FINANCE"));
      assertThat(created.at("/case/purchaseMethod").asText()).isEqualTo("FINANCE");
      assertThat(created.at("/case/buyerLimits").isNull()).isTrue();
    }
  }

  @Test
  void eachConsentPurposeKeepsIndependentGrantAndRevocationHistory() throws Exception {
    try (RunningApplication application = harness.start("consent-history")) {
      JsonNode created = createCase(application.client(), "consent-case", validCase());
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();

      assertThat(created.at("/case/consents")).hasSize(3);
      assertThat(created.at("/case/consents").findValuesAsText("purpose"))
          .containsExactlyInAnyOrder(
              "SERVICE_PROCESSING", "HUMAN_REVIEW", "ANONYMIZED_RESEARCH");
      assertThat(created.at("/case/consents").findValuesAsText("actor"))
          .containsOnly("BUYER");
      assertThat(created.at("/case/consents").findValuesAsText("source"))
          .containsOnly("BUYER_API");
      assertThat(created.at("/case/consents").findValuesAsText("schemaVersion"))
          .containsOnly("consent-record.v1");

      JsonNode granted = changeConsent(
          application.client(), caseId, accessToken,
          Map.of(
              "purpose", "HUMAN_REVIEW",
              "granted", true,
              "policyVersion", "human-review.v2"));
      assertThat(granted.get("consentVersion").asInt()).isEqualTo(2);
      assertThat(granted.get("action").asText()).isEqualTo("GRANTED");
      assertThat(granted.get("actor").asText()).isEqualTo("BUYER");
      assertThat(granted.get("source").asText()).isEqualTo("BUYER_API");
      assertThat(granted.get("schemaVersion").asText()).isEqualTo("consent-record.v1");

      JsonNode revoked = changeConsent(
          application.client(), caseId, accessToken,
          Map.of(
              "purpose", "HUMAN_REVIEW",
              "granted", false,
              "policyVersion", "human-review.v2"));
      assertThat(revoked.get("consentVersion").asInt()).isEqualTo(3);
      assertThat(revoked.get("action").asText()).isEqualTo("REVOKED");
      assertThat(revoked.get("actor").asText()).isEqualTo("BUYER");

      JsonNode reopened = application.client().get()
          .uri("/api/v1/cases/{caseId}", caseId)
          .header("Case-Access-Token", accessToken)
          .retrieve()
          .body(JsonNode.class);
      assertThat(reopened.get("consents").findValuesAsText("action"))
          .containsExactly("GRANTED", "DECLINED", "DECLINED", "GRANTED", "REVOKED");
    }
  }

  @Test
  void expiredCaseCredentialIsIndistinguishableFromAnInvalidCredential() throws Exception {
    try (RunningApplication application = harness.start(
        "expired-credential", true, "--case.access.lifetime=PT0S")) {
      JsonNode created = createCase(application.client(), "expiring-case", validCase());

      CapturedResponse expired = captureReopen(
          application.client(),
          created.at("/case/id").asText(),
          created.get("accessToken").asText());

      assertThat(expired.status()).isEqualTo(404);
      assertThat(expired.body()).doesNotContain("US-NC", "27604", "2500000");
    }
  }

  @Test
  void authenticatedCreationReplayReturnsTheSameCaseWithoutDisclosingTheCredential()
      throws Exception {
    try (RunningApplication application = harness.start("creation-idempotency")) {
      JsonNode first = createCase(application.client(), "same-creation", validCase());
      CapturedResponse unauthorized = captureCreate(
          application.client(), "same-creation", validCaseWithoutLimits("FINANCE"));
      JsonNode repeated = createCase(
          application.client(),
          "same-creation",
          first.get("accessToken").asText(),
          validCaseWithoutLimits("FINANCE"));

      assertThat(unauthorized.status()).isEqualTo(404);
      assertThat(unauthorized.body())
          .doesNotContain(first.get("accessToken").asText(), first.at("/case/id").asText());
      assertThat(repeated.at("/case/id")).isEqualTo(first.at("/case/id"));
      assertThat(repeated.get("accessToken").isNull()).isTrue();
      assertThat(Base64.getUrlDecoder().decode(first.get("accessToken").asText()))
          .hasSizeGreaterThanOrEqualTo(16);
      assertThat(repeated.at("/case/purchaseMethod").asText()).isEqualTo("CASH");
    }
  }

  @Test
  void buyerJurisdictionDealerLocationAndPurchaseMethodAreRequired() throws Exception {
    try (RunningApplication application = harness.start("required-starting-facts")) {
      Map<String, Object> missingJurisdiction = new HashMap<>(validCase());
      missingJurisdiction.remove("buyerJurisdiction");
      Map<String, Object> missingDealer = new HashMap<>(validCase());
      missingDealer.remove("dealerLocation");
      Map<String, Object> missingPurchaseMethod = new HashMap<>(validCase());
      missingPurchaseMethod.remove("purchaseMethod");

      assertThat(List.of(
          captureCreate(application.client(), "missing-jurisdiction", missingJurisdiction),
          captureCreate(application.client(), "missing-dealer", missingDealer),
          captureCreate(application.client(), "missing-method", missingPurchaseMethod)))
          .extracting(CapturedResponse::status)
          .containsExactly(400, 400, 400);
    }
  }

  private static JsonNode createCase(RestClient client, String idempotencyKey, Object request)
      throws Exception {
    String response = client.post()
        .uri("/api/v1/cases")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(request)
        .retrieve()
        .body(String.class);
    return JSON.readTree(response);
  }

  private static JsonNode createCase(
      RestClient client, String idempotencyKey, String accessToken, Object request)
      throws Exception {
    String response = client.post()
        .uri("/api/v1/cases")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .header("Case-Access-Token", accessToken)
        .body(request)
        .retrieve()
        .body(String.class);
    return JSON.readTree(response);
  }

  private static CapturedResponse captureCreate(
      RestClient client, String idempotencyKey, Object request) {
    return client.post()
        .uri("/api/v1/cases")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(request)
        .exchange((sent, response) -> new CapturedResponse(
            response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
  }

  private static JsonNode changeConsent(
      RestClient client, String caseId, String accessToken, Object request) {
    return client.post()
        .uri("/api/v1/cases/{caseId}/consents", caseId)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Case-Access-Token", accessToken)
        .body(request)
        .retrieve()
        .body(JsonNode.class);
  }

  private static CapturedResponse captureReopen(
      RestClient client, String caseId, String accessToken) {
    return client.get()
        .uri("/api/v1/cases/{caseId}", caseId)
        .header("Case-Access-Token", accessToken)
        .exchange((sent, response) -> new CapturedResponse(
            response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
  }

  private static Map<String, Object> validCase() {
    return Map.of(
        "buyerJurisdiction", "US-NC",
        "dealerLocation", "27604",
        "purchaseMethod", "CASH",
        "buyerLimits", Map.of("maxSellingPriceMinor", 25_000_00L),
        "consents", Map.of(
            "serviceProcessing", Map.of("granted", true, "policyVersion", "service.v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1")));
  }

  private static Map<String, Object> validCaseWithoutLimits(String purchaseMethod) {
    return Map.of(
        "buyerJurisdiction", "US-NC",
        "dealerLocation", "27604",
        "purchaseMethod", purchaseMethod,
        "consents", Map.of(
            "serviceProcessing", Map.of("granted", true, "policyVersion", "service.v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1")));
  }

  private record CapturedResponse(int status, String body) {}
}
