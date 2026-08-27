package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dealanalyzer.api.HttpAcceptanceHarness.awaitCompleted;
import static com.dealanalyzer.api.HttpAcceptanceHarness.get;
import static com.dealanalyzer.api.HttpAcceptanceHarness.post;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoldenCashDealContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "/fixtures/golden/cash-deal-standard.json",
      "/fixtures/golden/cash-deal-no-extras.json"
  })
  void frozenCashDealProducesAuditableCalculationContract(String fixtureResource) throws Exception {
    JsonNode fixture = JSON.readTree(getClass().getResourceAsStream(
        fixtureResource));
    JsonNode expected = fixture.get("expected");

    try (RunningApplication application = new HttpAcceptanceHarness(temporaryDirectory)
        .start("golden-contract-" + fixture.get("name").asText().hashCode())) {
      JsonNode started = post(
          application.client(), "/api/v1/cases/synthetic", fixture.get("input"));
      awaitCompleted(application.client(), started.at("/operation/id").asText());
      JsonNode report = get(application.client(), "/api/v1/cases/"
          + started.at("/case/id").asText() + "/reports/latest");

      assertThat(report.at("/analysis/economics/sellingPrice/minorUnits").asLong())
          .isEqualTo(expected.get("sellingPriceMinor").asLong());
      assertThat(report.at("/analysis/economics/dealerFees/minorUnits").asLong())
          .isEqualTo(expected.get("dealerFeesMinor").asLong());
      assertThat(report.at("/analysis/economics/addOns/minorUnits").asLong())
          .isEqualTo(expected.get("addOnsMinor").asLong());
      assertThat(report.at("/analysis/economics/taxes/minorUnits").asLong())
          .isEqualTo(expected.get("taxesMinor").asLong());
      assertThat(report.at("/analysis/economics/governmentCharges/minorUnits").asLong())
          .isEqualTo(expected.get("governmentChargesMinor").asLong());
      assertThat(report.at("/analysis/economics/outTheDoorTotal/minorUnits").asLong())
          .isEqualTo(expected.get("outTheDoorTotalMinor").asLong());
      assertThat(report.at("/analysis/economics/calculationVersion").asText())
          .isEqualTo(expected.get("calculationVersion").asText());
      assertThat(report.at("/analysis/economics/rounding"))
          .isEqualTo(expected.get("rounding"));
      assertThat(report.at("/analysis/economics/steps"))
          .isEqualTo(expected.get("steps"));
      assertThat(report.at("/analysis/policyVersions"))
          .isEqualTo(expected.get("policyVersions"));
      assertThat(report.get("schemaVersion").asText())
          .isEqualTo(expected.get("reportSchemaVersion").asText());
    }
  }

}
