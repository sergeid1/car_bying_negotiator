package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;
import com.dealanalyzer.market.MarketDataProvider;
import com.dealanalyzer.market.MarketEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class MarketAssessmentAcceptanceTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void buyerReceivesADurableVersionedAssessmentWithTransparentComparableDecisions()
      throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    String caseId;
    String accessToken;
    String assessmentId;
    try (RunningApplication application = harness.start("market-assessment")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-market-case");
      caseId = created.at("/case/id").asText();
      accessToken = created.get("accessToken").asText();
      HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of(
              "manualFacts", Map.of(
                  "year", 2021,
                  "make", "Honda",
                  "model", "Accord",
                  "trim", "EX-L",
                  "mileage", 38_000),
              "confirmed", true),
          "confirm-market-vehicle",
          accessToken);

      JsonNode assessment = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/market-assessments",
          Map.of(),
          "assess-market",
          accessToken);

      assessmentId = assessment.get("id").asText();
      assertThat(assessment.get("version").asInt()).isEqualTo(1);
      assertThat(assessment.get("status").asText()).isEqualTo("AVAILABLE");
      assertThat(assessment.at("/range/currency").asText()).isEqualTo("USD");
      assertThat(assessment.at("/range/lowMinor").asLong())
          .isLessThan(assessment.at("/range/highMinor").asLong());
      assertThat(assessment.get("geography").asText()).isEqualTo("Raleigh, NC");
      assertThat(assessment.at("/vintage/oldestObservationAt").asText()).isNotBlank();
      assertThat(assessment.at("/vintage/newestObservationAt").asText()).isNotBlank();
      assertThat(assessment.get("sampleSize").asInt()).isEqualTo(4);
      assertThat(assessment.get("methodVersion").asText()).isEqualTo("market-selection.v1");
      assertThat(assessment.get("confidence").asText()).isEqualTo("MEDIUM");
      assertThat(assessment.at("/provider/providerVersion").asText())
          .isEqualTo("deterministic-market-provider.v1");
      assertThat(assessment.at("/provider/licenseMetadata").asText())
          .contains("display and cache permitted");
      assertThat(assessment.has("providerPayload")).isFalse();

      JsonNode included = assessment.get("comparables");
      assertThat(included).hasSize(4);
      assertThat(included.get(0).get("matchFactors").isArray()).isTrue();
      assertThat(included.get(0).at("/adjustment/precision").asText()).isEqualTo("ROUNDED");
      assertThat(included.get(0).at("/adjustment/explanation").asText()).isNotBlank();

      JsonNode exclusions = assessment.get("exclusions");
      assertThat(exclusions).anySatisfy(exclusion ->
          assertThat(exclusion.get("reasonCode").asText()).isEqualTo("DUPLICATE"));
      assertThat(exclusions).anySatisfy(exclusion ->
          assertThat(exclusion.get("reasonCode").asText()).isEqualTo("STALE"));
      assertThat(exclusions).anySatisfy(exclusion ->
          assertThat(exclusion.get("reasonCode").asText()).isEqualTo("INCOMPATIBLE_VARIANT"));
      assertThat(exclusions).anySatisfy(exclusion ->
          assertThat(exclusion.get("reasonCode").asText()).isEqualTo("IMPLAUSIBLE_PRICE"));
    }

    try (RunningApplication restarted = harness.start("market-assessment")) {
      JsonNode latest = HttpAcceptanceHarness.get(
          restarted.client(),
          "/api/v1/cases/" + caseId + "/market-assessments/latest",
          accessToken);
      JsonNode version = HttpAcceptanceHarness.get(
          restarted.client(),
          "/api/v1/cases/" + caseId + "/market-assessments/1",
          accessToken);

      assertThat(latest.get("id").asText()).isEqualTo(assessmentId);
      assertThat(version).isEqualTo(latest);
      assertThat(version.get("comparables")).hasSize(4);
      assertThat(version.get("exclusions")).hasSizeGreaterThanOrEqualTo(4);
    }
  }

  @Test
  void providerFailurePreservesTheCaseAndCreatesAnExplicitlyUnavailableAssessment()
      throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.startWithConfiguration(
        "market-provider-failure", true, FailingProviderConfiguration.class)) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-provider-failure-case");
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();
      String originalStatus = created.at("/case/status").asText();
      HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of("manualFacts", Map.of(
              "year", 2021, "make", "Honda", "model", "Accord",
              "trim", "EX-L", "mileage", 38_000), "confirmed", true),
          "confirm-provider-failure-vehicle", accessToken);

      JsonNode assessment = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases/" + caseId + "/market-assessments",
          Map.of(), "failed-market-call", accessToken);
      JsonNode reopened = HttpAcceptanceHarness.get(
          application.client(), "/api/v1/cases/" + caseId, accessToken);

      assertThat(assessment.get("status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(assessment.get("range").isNull()).isTrue();
      assertThat(assessment.get("limitation").asText()).contains("provider failed");
      assertThat(reopened.get("status").asText()).isEqualTo(originalStatus);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailingProviderConfiguration {
    @Bean
    @Primary
    MarketDataProvider failingMarketDataProvider() {
      return new MarketDataProvider() {
        @Override
        public MarketEvidence assess(VehicleInput vehicle) {
          throw new IllegalStateException("timeout");
        }

        @Override
        public ProviderResult retrieve(MarketVehicle vehicle) {
          throw new IllegalStateException("timeout");
        }
      };
    }
  }

  private Map<String, Object> validCase() {
    return Map.of(
        "buyerJurisdiction", "NC",
        "dealerLocation", "Raleigh, NC",
        "purchaseMethod", "CASH",
        "consents", Map.of(
            "serviceProcessing", Map.of("granted", true, "policyVersion", "privacy-v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "privacy-v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "privacy-v1")));
  }
}
