package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpClientErrorException;

class VehicleEvidenceAcceptanceTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void buyerCanConfirmVehicleFromAValidVin() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("valid-vin")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-valid-vin");
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();

      JsonNode evidence = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of("vin", "1HGCM82633A004352", "confirmed", true),
          "confirm-valid-vin",
          accessToken);

      assertThat(evidence.at("/vehicle/vin").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(evidence.at("/vehicle/confirmationSource").asText()).isEqualTo("BUYER_VIN");
      assertThat(evidence.at("/vehicle/confirmedAt").asText()).isNotBlank();
      assertThat(evidence.get("reliableForAnalysis").asBoolean()).isTrue();
      assertThat(evidence.get("status").asText()).isEqualTo("VEHICLE_CONFIRMED");
    }
  }

  @Test
  void invalidVinChecksumReceivesActionableFeedback() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("invalid-vin")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-invalid-vin");

      HttpClientErrorException error = org.assertj.core.api.Assertions.catchThrowableOfType(
          () -> HttpAcceptanceHarness.post(
              application.client(),
              "/api/v1/cases/" + created.at("/case/id").asText() + "/vehicle-evidence",
              Map.of("vin", "1HGCM82633A004353", "confirmed", true),
              "confirm-invalid-vin",
              created.get("accessToken").asText()),
          HttpClientErrorException.class);

      assertThat(error.getStatusCode().value()).isEqualTo(400);
      JsonNode problem = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(error.getResponseBodyAsString());
      assertThat(problem.get("detail").asText())
          .contains("VIN checksum")
          .contains("check the VIN and try again");
    }
  }

  @Test
  void buyerCanConfirmVehicleFromStructuredManualFacts() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("manual-vehicle")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-manual-vehicle");

      JsonNode evidence = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + created.at("/case/id").asText() + "/vehicle-evidence",
          Map.of(
              "manualFacts", Map.of(
                  "year", 2021,
                  "make", "Honda",
                  "model", "Accord",
                  "trim", "EX-L",
                  "mileage", 38_000),
              "confirmed", true),
          "confirm-manual-vehicle",
          created.get("accessToken").asText());

      assertThat(evidence.at("/vehicle/year").asInt()).isEqualTo(2021);
      assertThat(evidence.at("/vehicle/make").asText()).isEqualTo("Honda");
      assertThat(evidence.at("/vehicle/model").asText()).isEqualTo("Accord");
      assertThat(evidence.at("/vehicle/trim").asText()).isEqualTo("EX-L");
      assertThat(evidence.at("/vehicle/mileage").asLong()).isEqualTo(38_000);
      assertThat(evidence.at("/vehicle/confirmationSource").asText()).isEqualTo("BUYER_MANUAL");
    }
  }

  @Test
  void supportedListingIsNormalizedIntoAnImmutableSnapshotWithProvenance() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("listing-snapshot")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-listing-snapshot");

      JsonNode evidence = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + created.at("/case/id").asText() + "/vehicle-evidence",
          Map.of(
              "vin", "1HGCM82633A004352",
              "listingUrl", "https://licensed.example/vehicles/1HGCM82633A004352",
              "confirmed", true),
          "snapshot-supported-listing",
          created.get("accessToken").asText());

      JsonNode snapshot = evidence.get("listingSnapshot");
      assertThat(snapshot.get("id").asText()).isNotBlank();
      assertThat(snapshot.get("vin").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(snapshot.get("advertisedPriceMinor").asLong()).isEqualTo(2_500_000);
      assertThat(snapshot.get("source").asText()).isEqualTo("DETERMINISTIC_LICENSED_FIXTURE");
      assertThat(snapshot.get("retrievedAt").asText()).isNotBlank();
      assertThat(snapshot.get("providerVersion").asText()).isEqualTo("listing-provider.v1");
      assertThat(snapshot.get("licenseMetadata").asText())
          .isEqualTo("synthetic fixture; display and cache permitted");
      assertThat(snapshot.at("/dealer/name").asText()).isEqualTo("Triangle Honda");
      assertThat(snapshot.has("providerPayload")).isFalse();
      assertThat(evidence.at("/listingRetrieval/status").asText())
          .isEqualTo("SNAPSHOT_CREATED");
    }
  }

  @Test
  void unsupportedListingFallsBackToVinWithoutPresentingRetrievedFacts() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("unsupported-listing")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-unsupported-listing");

      JsonNode evidence = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + created.at("/case/id").asText() + "/vehicle-evidence",
          Map.of(
              "vin", "1HGCM82633A004352",
              "listingUrl", "https://dealer.example/inventory/used-accord",
              "confirmed", true),
          "fallback-unsupported-listing",
          created.get("accessToken").asText());

      assertThat(evidence.get("listingSnapshot").isNull()).isTrue();
      assertThat(evidence.at("/listingRetrieval/status").asText())
          .isEqualTo("UNSUPPORTED_FALLBACK");
      assertThat(evidence.at("/listingRetrieval/fallback").asText()).isEqualTo("VIN");
      assertThat(evidence.at("/listingRetrieval/message").asText())
          .contains("not supported")
          .contains("VIN or manual facts");
      assertThat(evidence.get("reliableForAnalysis").asBoolean()).isTrue();
    }
  }

  @Test
  void vinMismatchBlocksReliableAnalysisUntilBuyerKeepsConfirmedVehicle() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("vin-conflict")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-vin-conflict");
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();

      JsonNode conflicted = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of(
              "vin", "1HGCM82633A004352",
              "listingUrl", "https://licensed.example/vehicles/1M8GDM9AXKP042788",
              "confirmed", true),
          "create-vin-mismatch",
          accessToken);

      assertThat(conflicted.get("status").asText()).isEqualTo("IDENTITY_CONFLICT");
      assertThat(conflicted.get("reliableForAnalysis").asBoolean()).isFalse();
      JsonNode conflict = conflicted.at("/identityConflicts/0");
      assertThat(conflict.get("field").asText()).isEqualTo("VIN");
      assertThat(conflict.get("confirmedValue").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(conflict.get("listingValue").asText()).isEqualTo("1M8GDM9AXKP042788");
      assertThat(conflict.get("status").asText()).isEqualTo("UNRESOLVED");

      JsonNode resolved = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence/conflicts/"
              + conflict.get("id").asText() + "/resolution",
          Map.of("resolution", "KEEP_CONFIRMED_VEHICLE"),
          "resolve-vin-mismatch",
          accessToken);

      assertThat(resolved.get("status").asText()).isEqualTo("VEHICLE_CONFIRMED");
      assertThat(resolved.get("reliableForAnalysis").asBoolean()).isTrue();
      assertThat(resolved.at("/vehicle/vin").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(resolved.at("/listingSnapshot/vin").asText()).isEqualTo("1M8GDM9AXKP042788");
      assertThat(resolved.at("/identityConflicts/0/status").asText())
          .isEqualTo("RESOLVED_KEEP_CONFIRMED");
    }
  }

  @Test
  void buyerCanResolveVinMismatchBySelectingTheSnapshottedListingVehicle() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start("use-listing-vehicle")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-use-listing");
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();
      JsonNode conflicted = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of(
              "vin", "1HGCM82633A004352",
              "listingUrl", "https://licensed.example/vehicles/1M8GDM9AXKP042788",
              "confirmed", true),
          "create-use-listing-mismatch",
          accessToken);

      JsonNode resolved = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence/conflicts/"
              + conflicted.at("/identityConflicts/0/id").asText() + "/resolution",
          Map.of("resolution", "USE_LISTING_VEHICLE"),
          "accept-listing-vehicle",
          accessToken);

      assertThat(resolved.at("/vehicle/vin").asText()).isEqualTo("1M8GDM9AXKP042788");
      assertThat(resolved.at("/vehicle/confirmationSource").asText())
          .isEqualTo("BUYER_LISTING");
      assertThat(resolved.at("/identityConflicts/0/status").asText())
          .isEqualTo("RESOLVED_USE_LISTING");
      assertThat(resolved.get("reliableForAnalysis").asBoolean()).isTrue();
    }
  }

  @Test
  void vehicleDealerAndListingSnapshotRemainAvailableAfterRestart() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    String caseId;
    String accessToken;
    String snapshotId;
    String retrievedAt;
    try (RunningApplication application = harness.start("durable-listing")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-durable-listing");
      caseId = created.at("/case/id").asText();
      accessToken = created.get("accessToken").asText();
      JsonNode evidence = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/vehicle-evidence",
          Map.of(
              "vin", "1HGCM82633A004352",
              "listingUrl", "https://licensed.example/vehicles/1HGCM82633A004352",
              "confirmed", true),
          "create-durable-snapshot",
          accessToken);
      snapshotId = evidence.at("/listingSnapshot/id").asText();
      retrievedAt = evidence.at("/listingSnapshot/retrievedAt").asText();
    }

    try (RunningApplication restarted = harness.start("durable-listing")) {
      JsonNode evidence = HttpAcceptanceHarness.get(
          restarted.client(), "/api/v1/cases/" + caseId + "/vehicle-evidence", accessToken);

      assertThat(evidence.at("/listingSnapshot/id").asText()).isEqualTo(snapshotId);
      assertThat(evidence.at("/listingSnapshot/retrievedAt").asText()).isEqualTo(retrievedAt);
      assertThat(evidence.at("/listingSnapshot/dealer/name").asText())
          .isEqualTo("Triangle Honda");
      assertThat(evidence.at("/vehicle/vin").asText()).isEqualTo("1HGCM82633A004352");
    }
  }

  private Map<String, Object> validCase() {
    return Map.of(
        "buyerPostalCode", "27601",
        "dealerPostalCode", "27604",
        "purchaseMethod", "CASH",
        "consents", Map.of(
            "serviceProcessing", Map.of("granted", true, "policyVersion", "privacy-v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "privacy-v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "privacy-v1")));
  }
}
