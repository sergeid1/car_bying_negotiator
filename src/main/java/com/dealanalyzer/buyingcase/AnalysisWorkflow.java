package com.dealanalyzer.buyingcase;

import com.dealanalyzer.buyingcase.BuyingCaseStore.OperationView;
import com.dealanalyzer.buyingcase.BuyingCaseStore.StoredCase;
import com.dealanalyzer.buyingcase.BuyingCaseStore.CaseStatus;
import com.dealanalyzer.buyingcase.BuyingCaseStore.PurchaseMethod;
import com.dealanalyzer.report.CashDealAnalyzer;
import com.dealanalyzer.report.DealAnalysis;
import com.dealanalyzer.report.ReportAssembler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

@Service
public class AnalysisWorkflow {
  private final BuyingCaseStore store;
  private final CashDealAnalyzer analyzer;
  private final ObjectMapper json;
  private final TaskExecutor taskExecutor;
  private final ReportAssembler reportAssembler;
  private final boolean workerEnabled;

  public AnalysisWorkflow(
      BuyingCaseStore store,
      CashDealAnalyzer analyzer,
      ReportAssembler reportAssembler,
      ObjectMapper json,
      TaskExecutor taskExecutor,
      @Value("${analysis.worker.enabled:true}") boolean workerEnabled) {
    this.store = store;
    this.analyzer = analyzer;
    this.json = json.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    this.taskExecutor = taskExecutor;
    this.reportAssembler = reportAssembler;
    this.workerEnabled = workerEnabled;
  }

  public StartedAnalysis start(SyntheticAnalysisRequest input, String idempotencyKey) {
    Optional<OperationView> existing = store.findOperationByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      OperationView operation = existing.orElseThrow();
      return new StartedAnalysis(caseView(store.requireCase(operation.caseId())), operation);
    }
    String caseId = UUID.randomUUID().toString();
    String operationId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    String inputJson = write(input);
    try {
      store.createCase(
          caseId, operationId, idempotencyKey, input, inputJson, sha256(inputJson), now);
    } catch (DuplicateKeyException duplicate) {
      OperationView operation = store.findOperationByIdempotencyKey(idempotencyKey)
          .orElseThrow(() -> duplicate);
      return new StartedAnalysis(caseView(store.requireCase(operation.caseId())), operation);
    }
    StartedAnalysis started =
        new StartedAnalysis(caseView(store.requireCase(caseId)), store.requireOperation(operationId));
    dispatch(caseId, operationId);
    return started;
  }

  public StartedAnalysis regenerate(String caseId, String idempotencyKey) {
    Optional<OperationView> existing = store.findOperationByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      OperationView operation = existing.orElseThrow();
      if (!operation.caseId().equals(caseId)) {
        throw new IdempotencyConflictException();
      }
      return new StartedAnalysis(caseView(store.requireCase(caseId)), operation);
    }
    store.requireCase(caseId);
    String operationId = UUID.randomUUID().toString();
    try {
      store.createOperation(operationId, caseId, idempotencyKey, Instant.now());
    } catch (DuplicateKeyException duplicate) {
      OperationView operation = store.findOperationByIdempotencyKey(idempotencyKey)
          .orElseThrow(() -> duplicate);
      if (!operation.caseId().equals(caseId)) {
        throw new IdempotencyConflictException();
      }
      return new StartedAnalysis(caseView(store.requireCase(caseId)), operation);
    }
    StartedAnalysis started =
        new StartedAnalysis(caseView(store.requireCase(caseId)), store.requireOperation(operationId));
    dispatch(caseId, operationId);
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

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedOperations() {
    if (workerEnabled) {
      store.findRecoverableOperations().forEach(operation ->
          taskExecutor.execute(() -> run(operation.caseId(), operation.id())));
    }
  }

  private void dispatch(String caseId, String operationId) {
    if (workerEnabled) {
      taskExecutor.execute(() -> run(caseId, operationId));
    }
  }

  private void run(String caseId, String operationId) {
    try {
      store.updateStage(operationId, OperationStage.LOADING_INPUTS, Instant.now());
      StoredCase buyingCase = store.requireCase(caseId);
      SyntheticAnalysisRequest input = json.readValue(buyingCase.inputJson(), SyntheticAnalysisRequest.class);
      store.updateStage(operationId, OperationStage.FETCHING_MARKET, Instant.now());
      DealAnalysis analysis = analyzer.analyze(input);
      store.updateStage(operationId, OperationStage.CALCULATING, Instant.now());
      store.updateStage(operationId, OperationStage.ASSEMBLING_REPORT, Instant.now());
      int version = buyingCase.currentReportVersion() + 1;
      var report = reportAssembler.assemble(
          caseId, version, buyingCase.inputFingerprint(), analysis);
      store.complete(
          caseId, operationId, version, report.payloadJson(), report.analysisFingerprint(),
          report.generatedAt());
    } catch (Exception exception) {
      store.fail(caseId, operationId, "Analysis could not be completed", Instant.now());
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

  public enum OperationStage {
    QUEUED, LOADING_INPUTS, FETCHING_MARKET, CALCULATING, ASSEMBLING_REPORT, COMPLETE, FAILED
  }

  public record CaseView(
      String id,
      String buyerZip,
      String dealerZip,
      PurchaseMethod purchaseMethod,
      CaseStatus status,
      JsonNode vehicle,
      JsonNode dealerOffer,
      int currentReportVersion,
      Instant createdAt,
      Instant updatedAt) {}
}
