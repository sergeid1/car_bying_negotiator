package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dealanalyzer.api.HttpAcceptanceHarness.awaitCompleted;
import static com.dealanalyzer.api.HttpAcceptanceHarness.get;
import static com.dealanalyzer.api.HttpAcceptanceHarness.post;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.dealanalyzer.evaluation.ReportReplayEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class SyntheticCashDealAcceptanceTest {
  @TempDir Path temporaryDirectory;
  private HttpAcceptanceHarness harness;

  @BeforeEach
  void createHttpHarness() {
    harness = new HttpAcceptanceHarness(temporaryDirectory);
  }

  @Test
  void buyerCanCompleteSyntheticCashWorkflowAndRegenerateTheSameAnalysis() throws Exception {
    try (RunningApplication application = harness.start("workflow")) {
      RestClient client = application.client();

      JsonNode started = post(client, "/api/v1/cases/synthetic", syntheticCashDeal());
      String accessToken = started.get("accessToken").asText();
      assertThat(started.at("/case/status").asText()).isEqualTo("ANALYZING");
      assertThat(started.at("/operation/stage").asText()).isIn("QUEUED", "LOADING_INPUTS");

      JsonNode completedOperation =
          awaitCompleted(client, started.at("/operation/id").asText(), accessToken);
      assertThat(completedOperation.get("stage").asText()).isEqualTo("COMPLETE");

      String caseId = started.at("/case/id").asText();
      JsonNode firstReport =
          get(client, "/api/v1/cases/" + caseId + "/reports/latest", accessToken);
      assertThat(firstReport.get("schemaVersion").asText()).isEqualTo("deal-analysis-report.v1");
      assertThat(firstReport.get("version").asInt()).isEqualTo(1);
      assertThat(firstReport.at("/analysis/economics/outTheDoorTotal/minorUnits").asLong())
          .isEqualTo(28_475_00L);
      assertThat(firstReport.at("/analysis/economics/outTheDoorTotal/precision").asText())
          .isEqualTo("EXACT");

      JsonNode regeneration =
          post(
              client,
              "/api/v1/cases/" + caseId + "/analyses",
              Map.of(),
              java.util.UUID.randomUUID().toString(),
              accessToken);
      awaitCompleted(client, regeneration.at("/operation/id").asText(), accessToken);
      JsonNode secondReport =
          get(client, "/api/v1/cases/" + caseId + "/reports/latest", accessToken);

      assertThat(secondReport.get("version").asInt()).isEqualTo(2);
      new ReportReplayEvaluator().assertReproduces(firstReport, secondReport);
      assertThat(secondReport.get("analysis")).isEqualTo(firstReport.get("analysis"));
      assertThat(secondReport.at("/reproduction/inputFingerprint").asText())
          .isEqualTo(firstReport.at("/reproduction/inputFingerprint").asText());
      assertThat(secondReport.at("/reproduction/analysisFingerprint").asText())
          .isEqualTo(firstReport.at("/reproduction/analysisFingerprint").asText());
      assertThat(secondReport.get("coverage")).isEqualTo(firstReport.get("coverage"));
      assertThat(secondReport.at("/reproduction/expectedVersionDifferences").fieldNames())
          .toIterable()
          .containsExactlyInAnyOrder("id", "version", "generatedAt");

      JsonNode originalReport =
          get(client, "/api/v1/cases/" + caseId + "/reports/1", accessToken);
      assertThat(originalReport).isEqualTo(firstReport);
    }
  }

  @Test
  void duplicateAnalysisRequestReturnsTheOriginalOperationAndReportVersion() throws Exception {
    try (RunningApplication application = harness.start("idempotency")) {
      String key = "same-analysis-request";
      JsonNode first = post(application.client(), "/api/v1/cases/synthetic", syntheticCashDeal(), key);
      String accessToken = first.get("accessToken").asText();
      List<String> unauthorizedBodies = new ArrayList<>();
      int unauthorizedStatus = application.client().post()
          .uri("/api/v1/cases/synthetic")
          .contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", key)
          .body(syntheticCashDeal())
          .exchange((request, response) -> {
            unauthorizedBodies.add(new String(response.getBody().readAllBytes()));
            return response.getStatusCode().value();
          });
      JsonNode duplicate = post(
          application.client(),
          "/api/v1/cases/synthetic",
          syntheticCashDeal(),
          key,
          accessToken);

      assertThat(unauthorizedStatus).isEqualTo(404);
      assertThat(unauthorizedBodies.getFirst())
          .doesNotContain(accessToken, first.at("/case/id").asText());
      assertThat(duplicate.at("/case/id").asText()).isEqualTo(first.at("/case/id").asText());
      assertThat(duplicate.at("/operation/id").asText())
          .isEqualTo(first.at("/operation/id").asText());
      assertThat(duplicate.get("accessToken").isNull()).isTrue();
      awaitCompleted(application.client(), first.at("/operation/id").asText(), accessToken);
      assertThat(get(
              application.client(),
              "/api/v1/cases/" + first.at("/case/id").asText(),
              accessToken)
          .get("currentReportVersion").asInt()).isEqualTo(1);
    }
  }

  @Test
  void reportReasonsAreDerivedFromTheRecordedOffer() throws Exception {
    try (RunningApplication application = harness.start("derived-reasons")) {
      Map<String, Object> input = syntheticCashDeal(28_000_00, 100_00, 200_00);
      JsonNode started = post(application.client(), "/api/v1/cases/synthetic", input);
      String accessToken = started.get("accessToken").asText();
      awaitCompleted(
          application.client(), started.at("/operation/id").asText(), accessToken);
      JsonNode report = get(application.client(),
          "/api/v1/cases/" + started.at("/case/id").asText() + "/reports/latest",
          accessToken);

      assertThat(report.at("/analysis/dealRating").asText()).isEqualTo("POOR_DEAL");
      assertThat(report.at("/analysis/topReasons/0").asText()).contains("above");
      assertThat(report.at("/analysis/topReasons/1").asText()).contains("$300.00");
      assertThat(report.at("/analysis/topReasons/2").asText()).contains("$30,476.00");
    }
  }

  @Test
  void queuedOperationIsRecoveredAfterProcessRestart() throws Exception {
    String caseId;
    String operationId;
    String accessToken;
    try (RunningApplication stoppedBeforeWork = harness.start("recovery", false)) {
      JsonNode started = post(
          stoppedBeforeWork.client(), "/api/v1/cases/synthetic", syntheticCashDeal());
      caseId = started.at("/case/id").asText();
      operationId = started.at("/operation/id").asText();
      accessToken = started.get("accessToken").asText();
      assertThat(get(
              stoppedBeforeWork.client(),
              "/api/v1/operations/" + operationId,
              accessToken)
          .get("stage").asText()).isEqualTo("QUEUED");
    }

    try (RunningApplication restarted = harness.start("recovery", true)) {
      awaitCompleted(restarted.client(), operationId, accessToken);
      assertThat(get(restarted.client(), "/api/v1/cases/" + caseId, accessToken)
          .get("status").asText()).isEqualTo("COMPLETE");
      assertThat(get(
              restarted.client(),
              "/api/v1/cases/" + caseId + "/reports/latest",
              accessToken)
          .get("version").asInt()).isEqualTo(1);
    }
  }

  @Test
  void buyingCaseOperationInputsAndImmutableReportSurviveAProcessRestart() throws Exception {
    String caseId;
    String accessToken;
    JsonNode reportBeforeRestart;

    try (RunningApplication firstProcess = harness.start("restart")) {
      JsonNode started =
          post(firstProcess.client(), "/api/v1/cases/synthetic", syntheticCashDeal());
      caseId = started.at("/case/id").asText();
      accessToken = started.get("accessToken").asText();
      awaitCompleted(
          firstProcess.client(), started.at("/operation/id").asText(), accessToken);
      reportBeforeRestart =
          get(
              firstProcess.client(),
              "/api/v1/cases/" + caseId + "/reports/latest",
              accessToken);
    }

    try (RunningApplication restartedProcess = harness.start("restart")) {
      JsonNode buyingCase =
          get(restartedProcess.client(), "/api/v1/cases/" + caseId, accessToken);
      JsonNode reportAfterRestart =
          get(
              restartedProcess.client(),
              "/api/v1/cases/" + caseId + "/reports/latest",
              accessToken);

      assertThat(buyingCase.get("status").asText()).isEqualTo("COMPLETE");
      assertThat(buyingCase.at("/vehicle/make").asText()).isEqualTo("Honda");
      assertThat(buyingCase.at("/dealerOffer/sellingPriceMinor").asLong()).isEqualTo(25_000_00L);
      assertThat(reportAfterRestart).isEqualTo(reportBeforeRestart);
    }
  }

  private static Map<String, Object> syntheticCashDeal() {
    return syntheticCashDeal(25_000_00, 799_00, 500_00);
  }

  private static Map<String, Object> syntheticCashDeal(
      int sellingPriceMinor, int dealerFeesMinor, int addOnsMinor) {
    return Map.of(
        "buyerZip", "27601",
        "dealerZip", "27604",
        "vehicle",
            Map.of(
                "vin", "1HGCM82633A004352",
                "year", 2021,
                "make", "Honda",
                "model", "Accord",
                "trim", "EX-L",
                "mileage", 38_000),
        "dealerOffer",
            Map.of(
                "currency", "USD",
                "sellingPriceMinor", sellingPriceMinor,
                "dealerFeesMinor", dealerFeesMinor,
                "addOnsMinor", addOnsMinor,
                "taxesMinor", 1_946_00,
                "governmentChargesMinor", 230_00));
  }
}
