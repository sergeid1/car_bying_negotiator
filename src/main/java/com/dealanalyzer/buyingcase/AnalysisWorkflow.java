package com.dealanalyzer.buyingcase;

import com.dealanalyzer.buyingcase.BuyingCaseStore.OperationView;
import com.dealanalyzer.buyingcase.BuyingCaseStore.StoredCase;
import com.dealanalyzer.report.CashDealAnalyzer;
import com.dealanalyzer.report.DealAnalysis;
import com.dealanalyzer.report.ReportPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class AnalysisWorkflow {
  private final BuyingCaseStore store;
  private final CashDealAnalyzer analyzer;
  private final ObjectMapper json;
  private final TaskExecutor taskExecutor;
  private final Clock clock;

  public AnalysisWorkflow(
      BuyingCaseStore store,
      CashDealAnalyzer analyzer,
      ObjectMapper json,
      TaskExecutor taskExecutor) {
    this.store = store;
    this.analyzer = analyzer;
    this.json = json.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    this.taskExecutor = taskExecutor;
    this.clock = Clock.systemUTC();
  }

  public StartedAnalysis start(SyntheticAnalysisRequest input) {
    String caseId = UUID.randomUUID().toString();
    String operationId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    String inputJson = write(input);
    store.createCase(caseId, operationId, input, inputJson, sha256(inputJson), now);
    StartedAnalysis started =
        new StartedAnalysis(caseView(store.requireCase(caseId)), store.requireOperation(operationId));
    taskExecutor.execute(() -> run(caseId, operationId));
    return started;
  }

  public StartedAnalysis regenerate(String caseId) {
    store.requireCase(caseId);
    String operationId = UUID.randomUUID().toString();
    store.createOperation(operationId, caseId, clock.instant());
    StartedAnalysis started =
        new StartedAnalysis(caseView(store.requireCase(caseId)), store.requireOperation(operationId));
    taskExecutor.execute(() -> run(caseId, operationId));
    return started;
  }

  public CaseView getCase(String caseId) {
    return caseView(store.requireCase(caseId));
  }

  public OperationView getOperation(String operationId) {
    return store.requireOperation(operationId);
  }

  public JsonNode getReport(String caseId, Optional<Integer> version) {
    return store.requireReport(caseId, version);
  }

  private void run(String caseId, String operationId) {
    try {
      store.updateStage(operationId, "LOADING_INPUTS", clock.instant());
      StoredCase buyingCase = store.requireCase(caseId);
      SyntheticAnalysisRequest input = json.readValue(buyingCase.inputJson(), SyntheticAnalysisRequest.class);
      store.updateStage(operationId, "FETCHING_MARKET", clock.instant());
      DealAnalysis analysis = analyzer.analyze(input);
      store.updateStage(operationId, "CALCULATING", clock.instant());
      String analysisJson = write(analysis);
      String analysisFingerprint = sha256(analysisJson);
      store.updateStage(operationId, "ASSEMBLING_REPORT", clock.instant());
      int version = buyingCase.currentReportVersion() + 1;
      Instant generatedAt = clock.instant();
      ReportPayload report =
          new ReportPayload(
              UUID.randomUUID().toString(),
              caseId,
              "deal-analysis-report.v1",
              version,
              generatedAt,
              analysis,
              new ReportPayload.Reproduction(
                  buyingCase.inputFingerprint(),
                  analysisFingerprint,
                  Map.of(
                      "id", "new immutable report identity",
                      "version", "incremented report version",
                      "generatedAt", "new generation timestamp")),
              Map.of(
                  "jurisdiction", "Synthetic jurisdiction fixture",
                  "asOf", generatedAt.toString()));
      store.complete(
          caseId, operationId, version, write(report), analysisFingerprint, generatedAt);
    } catch (Exception exception) {
      store.fail(caseId, operationId, "Analysis could not be completed", clock.instant());
    }
  }

  private CaseView caseView(StoredCase stored) {
    return new CaseView(
        stored.id(),
        stored.buyerZip(),
        stored.dealerZip(),
        stored.purchaseMethod(),
        stored.status(),
        stored.vehicle(),
        stored.dealerOffer(),
        stored.currentReportVersion(),
        stored.createdAt(),
        stored.updatedAt());
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize structured analysis", exception);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record StartedAnalysis(@JsonProperty("case") CaseView buyingCase, OperationView operation) {}

  public record CaseView(
      String id,
      String buyerZip,
      String dealerZip,
      String purchaseMethod,
      String status,
      JsonNode vehicle,
      JsonNode dealerOffer,
      int currentReportVersion,
      Instant createdAt,
      Instant updatedAt) {}
}
