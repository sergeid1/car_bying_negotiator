package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.DealAnalyzerApplication;
import com.dealanalyzer.evaluation.ReportReplayEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class SyntheticCashDealAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void buyerCanCompleteSyntheticCashWorkflowAndRegenerateTheSameAnalysis() throws Exception {
    try (RunningApplication application = start("workflow")) {
      RestClient client = application.client();

      JsonNode started = post(client, "/api/v1/cases/synthetic", syntheticCashDeal());
      assertThat(started.at("/case/status").asText()).isEqualTo("ANALYZING");
      assertThat(started.at("/operation/stage").asText()).isIn("QUEUED", "LOADING_INPUTS");

      JsonNode completedOperation = awaitCompleted(client, started.at("/operation/id").asText());
      assertThat(completedOperation.get("stage").asText()).isEqualTo("COMPLETE");

      String caseId = started.at("/case/id").asText();
      JsonNode firstReport = get(client, "/api/v1/cases/" + caseId + "/reports/latest");
      assertThat(firstReport.get("schemaVersion").asText()).isEqualTo("deal-analysis-report.v1");
      assertThat(firstReport.get("version").asInt()).isEqualTo(1);
      assertThat(firstReport.at("/analysis/economics/outTheDoorTotal/minorUnits").asLong())
          .isEqualTo(28_475_00L);
      assertThat(firstReport.at("/analysis/economics/outTheDoorTotal/precision").asText())
          .isEqualTo("EXACT");

      JsonNode regeneration =
          post(client, "/api/v1/cases/" + caseId + "/analyses", Map.of());
      awaitCompleted(client, regeneration.at("/operation/id").asText());
      JsonNode secondReport = get(client, "/api/v1/cases/" + caseId + "/reports/latest");

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

      JsonNode originalReport = get(client, "/api/v1/cases/" + caseId + "/reports/1");
      assertThat(originalReport).isEqualTo(firstReport);
    }
  }

  @Test
  void duplicateAnalysisRequestReturnsTheOriginalOperationAndReportVersion() throws Exception {
    try (RunningApplication application = start("idempotency")) {
      String key = "same-analysis-request";
      JsonNode first = post(application.client(), "/api/v1/cases/synthetic", syntheticCashDeal(), key);
      JsonNode duplicate = post(application.client(), "/api/v1/cases/synthetic", syntheticCashDeal(), key);

      assertThat(duplicate.at("/case/id").asText()).isEqualTo(first.at("/case/id").asText());
      assertThat(duplicate.at("/operation/id").asText())
          .isEqualTo(first.at("/operation/id").asText());
      awaitCompleted(application.client(), first.at("/operation/id").asText());
      assertThat(get(application.client(), "/api/v1/cases/" + first.at("/case/id").asText())
          .get("currentReportVersion").asInt()).isEqualTo(1);
    }
  }

  @Test
  void reportReasonsAreDerivedFromTheRecordedOffer() throws Exception {
    try (RunningApplication application = start("derived-reasons")) {
      Map<String, Object> input = syntheticCashDeal(28_000_00, 100_00, 200_00);
      JsonNode started = post(application.client(), "/api/v1/cases/synthetic", input);
      awaitCompleted(application.client(), started.at("/operation/id").asText());
      JsonNode report = get(application.client(),
          "/api/v1/cases/" + started.at("/case/id").asText() + "/reports/latest");

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
    try (RunningApplication stoppedBeforeWork = start("recovery", false)) {
      JsonNode started = post(
          stoppedBeforeWork.client(), "/api/v1/cases/synthetic", syntheticCashDeal());
      caseId = started.at("/case/id").asText();
      operationId = started.at("/operation/id").asText();
      assertThat(get(stoppedBeforeWork.client(), "/api/v1/operations/" + operationId)
          .get("stage").asText()).isEqualTo("QUEUED");
    }

    try (RunningApplication restarted = start("recovery", true)) {
      awaitCompleted(restarted.client(), operationId);
      assertThat(get(restarted.client(), "/api/v1/cases/" + caseId)
          .get("status").asText()).isEqualTo("COMPLETE");
      assertThat(get(restarted.client(), "/api/v1/cases/" + caseId + "/reports/latest")
          .get("version").asInt()).isEqualTo(1);
    }
  }

  @Test
  void buyingCaseOperationInputsAndImmutableReportSurviveAProcessRestart() throws Exception {
    String caseId;
    JsonNode reportBeforeRestart;

    try (RunningApplication firstProcess = start("restart")) {
      JsonNode started =
          post(firstProcess.client(), "/api/v1/cases/synthetic", syntheticCashDeal());
      caseId = started.at("/case/id").asText();
      awaitCompleted(firstProcess.client(), started.at("/operation/id").asText());
      reportBeforeRestart =
          get(firstProcess.client(), "/api/v1/cases/" + caseId + "/reports/latest");
    }

    try (RunningApplication restartedProcess = start("restart")) {
      JsonNode buyingCase = get(restartedProcess.client(), "/api/v1/cases/" + caseId);
      JsonNode reportAfterRestart =
          get(restartedProcess.client(), "/api/v1/cases/" + caseId + "/reports/latest");

      assertThat(buyingCase.get("status").asText()).isEqualTo("COMPLETE");
      assertThat(buyingCase.at("/vehicle/make").asText()).isEqualTo("Honda");
      assertThat(buyingCase.at("/dealerOffer/sellingPriceMinor").asLong()).isEqualTo(25_000_00L);
      assertThat(reportAfterRestart).isEqualTo(reportBeforeRestart);
    }
  }

  private RunningApplication start(String databaseName) {
    return start(databaseName, true);
  }

  private RunningApplication start(String databaseName, boolean workerEnabled) {
    String dataSourceUrl =
        "jdbc:h2:file:"
            + temporaryDirectory.resolve(databaseName).toAbsolutePath()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(DealAnalyzerApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + dataSourceUrl,
                "--analysis.worker.enabled=" + workerEnabled,
                "--spring.task.execution.pool.core-size=1");
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new RunningApplication(context, RestClient.create("http://localhost:" + port));
  }

  private static JsonNode awaitCompleted(RestClient client, String operationId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode operation;
    do {
      operation = get(client, "/api/v1/operations/" + operationId);
      if (operation.get("stage").asText().equals("COMPLETE")) {
        return operation;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Operation did not complete: " + operation);
  }

  private static JsonNode post(RestClient client, String path, Object body) throws Exception {
    return post(client, path, body, java.util.UUID.randomUUID().toString());
  }

  private static JsonNode post(RestClient client, String path, Object body, String idempotencyKey)
      throws Exception {
    String response =
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(body)
            .retrieve()
            .body(String.class);
    return JSON.readTree(response);
  }

  private static JsonNode get(RestClient client, String path) throws Exception {
    return JSON.readTree(client.get().uri(path).retrieve().body(String.class));
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

  private record RunningApplication(ConfigurableApplicationContext context, RestClient client)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }
}
