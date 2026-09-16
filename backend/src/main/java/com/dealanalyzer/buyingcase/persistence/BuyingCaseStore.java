package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.AnalysisWorkflow.OperationStage;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BuyingCaseStore {
  private static final List<String> TERMINAL_STAGES = List.of("COMPLETE", "FAILED");
  @jakarta.persistence.PersistenceContext
  private jakarta.persistence.EntityManager entityManager;

  private final AnalysisCaseRepository cases;
  private final OperationRepository operations;
  private final AnalysisReportRepository reports;
  private final ObjectMapper json;

  BuyingCaseStore(
      AnalysisCaseRepository cases,
      OperationRepository operations,
      AnalysisReportRepository reports,
      ObjectMapper json) {
    this.cases = cases;
    this.operations = operations;
    this.reports = reports;
    this.json = json;
  }

  @Transactional
  public void createCase(
      String caseId,
      String operationId,
      String idempotencyKey,
      SyntheticAnalysisRequest input,
      String inputJson,
      String inputFingerprint,
      String accessTokenHash,
      Instant accessExpiresAt,
      Instant now) {
    AnalysisCaseEntity buyingCase = AnalysisCaseEntity.synthetic(
        caseId,
        input.buyerZip(),
        input.dealerZip(),
        write(input.vehicle()),
        write(input.dealerOffer()),
        inputJson,
        inputFingerprint,
        accessTokenHash,
        accessExpiresAt,
        now);
    cases.save(buyingCase);
    operations.saveAndFlush(operation(operationId, caseId, idempotencyKey, now));
  }

  @Transactional
  public void createOperation(
      String operationId, String caseId, String idempotencyKey, Instant now) {
    AnalysisCaseEntity buyingCase = requireLockedCase(caseId);
    buyingCase.status = "ANALYZING";
    buyingCase.updatedAt = now;
    operations.saveAndFlush(operation(operationId, caseId, idempotencyKey, now));
  }

  @Transactional(readOnly = true)
  public StoredCase requireCase(String caseId) {
    return findCase(caseId).orElseThrow(this::caseNotFound);
  }

  @Transactional(readOnly = true)
  public Optional<StoredCase> findCase(String caseId) {
    return cases.findById(caseId).map(this::stored);
  }

  @Transactional(readOnly = true)
  public OperationView requireOperation(String operationId) {
    return operationView(operations.findById(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Operation not found")));
  }

  @Transactional(readOnly = true)
  public Optional<OperationView> findOperationByIdempotencyKey(String idempotencyKey) {
    return operations.findByIdempotencyKey(idempotencyKey).map(this::operationView);
  }

  @Transactional(readOnly = true)
  public List<OperationView> findRecoverableOperations(String afterId) {
    return operations.findRecoverableAfter(TERMINAL_STAGES, afterId,
        org.springframework.data.domain.PageRequest.of(0, 100)).stream()
        .map(this::operationView)
        .toList();
  }

  @Transactional
  public void updateStage(String operationId, OperationStage stage, Instant now) {
    OperationEntity operation = operations.findById(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Operation not found"));
    requireLockedCase(operation.caseId);
    // A worker may have completed while this transaction waited for the case lock.
    entityManager.refresh(operation);
    if ("COMPLETE".equals(operation.stage)) return;
    operation.stage = stage.name();
    operation.updatedAt = now;
  }

  @Transactional
  public void complete(
      String caseId,
      String operationId,
      int version,
      String payloadJson,
      String analysisFingerprint,
      Instant now) {
    AnalysisCaseEntity buyingCase = requireLockedCase(caseId);
    OperationEntity operation = operations.findById(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Operation not found"));
    if (!caseId.equals(operation.caseId)) throw new ResourceNotFoundException("Operation not found");
    if ("COMPLETE".equals(operation.stage)) return;
    version = buyingCase.currentReportVersion + 1;
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) readTree(payloadJson);
    payload.put("version", version);
    reports.saveAndFlush(new AnalysisReportEntity(
        caseId, version, write(payload), analysisFingerprint, now, operationId));
    buyingCase.status = "COMPLETE";
    buyingCase.currentReportVersion = version;
    buyingCase.updatedAt = now;
    operation.stage = OperationStage.COMPLETE.name();
    operation.updatedAt = now;
  }

  @Transactional
  public void fail(String caseId, String operationId, String safeMessage, Instant now) {
    fail(caseId, operationId, safeMessage, now, com.dealanalyzer.operations.ProviderMeter.Status.INTERNAL);
  }

  @Transactional
  public void fail(String caseId, String operationId, String safeMessage, Instant now,
      com.dealanalyzer.operations.ProviderMeter.Status category) {
    AnalysisCaseEntity buyingCase = requireLockedCase(caseId);
    OperationEntity operation = operations.findById(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Operation not found"));
    if (!caseId.equals(operation.caseId)) throw new ResourceNotFoundException("Operation not found");
    if ("COMPLETE".equals(operation.stage)) return;
    buyingCase.status = "FAILED";
    buyingCase.updatedAt = now;
    operation.stage = OperationStage.FAILED.name();
    operation.errorMessage = safeMessage;
    operation.failureCategory = category.name();
    operation.updatedAt = now;
  }

  @Transactional(readOnly = true)
  public JsonNode requireReport(String caseId, Optional<Integer> requestedVersion) {
    AnalysisCaseEntity buyingCase = cases.findById(caseId).orElseThrow(this::caseNotFound);
    int version = requestedVersion.orElse(buyingCase.currentReportVersion);
    if (version == 0) {
      throw new ResourceNotFoundException("Deal Analysis Report not available");
    }
    return reports.findById(new AnalysisReportKey(caseId, version))
        .map(report -> readTree(report.payloadJson))
        .orElseThrow(() -> new ResourceNotFoundException("Deal Analysis Report not found"));
  }

  private AnalysisCaseEntity requireLockedCase(String caseId) {
    return cases.findLockedById(caseId).orElseThrow(this::caseNotFound);
  }

  private OperationEntity operation(
      String operationId, String caseId, String idempotencyKey, Instant now) {
    return new OperationEntity(
        operationId, caseId, idempotencyKey, OperationStage.QUEUED.name(), now);
  }

  private StoredCase stored(AnalysisCaseEntity entity) {
    return new StoredCase(
        entity.id,
        entity.buyerZip,
        entity.dealerZip,
        PurchaseMethod.valueOf(entity.purchaseMethod),
        CaseStatus.valueOf(entity.status),
        readTree(entity.vehicleJson),
        readTree(entity.dealerOfferJson),
        entity.inputJson,
        entity.inputFingerprint,
        entity.accessTokenHash,
        entity.accessExpiresAt,
        entity.currentReportVersion,
        entity.createdAt,
        entity.updatedAt);
  }

  private OperationView operationView(OperationEntity entity) {
    return new OperationView(
        entity.id,
        entity.caseId,
        OperationStage.valueOf(entity.stage),
        entity.errorMessage,
        entity.createdAt,
        entity.updatedAt);
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Unable to serialize structured input", exception);
    }
  }

  private JsonNode readTree(String value) {
    if (value == null) {
      return null;
    }
    try {
      return json.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored structured data is invalid", exception);
    }
  }

  private ResourceNotFoundException caseNotFound() {
    return new ResourceNotFoundException("Buying Case not found");
  }

  public record StoredCase(
      String id,
      String buyerZip,
      String dealerZip,
      PurchaseMethod purchaseMethod,
      CaseStatus status,
      JsonNode vehicle,
      JsonNode dealerOffer,
      String inputJson,
      String inputFingerprint,
      String accessTokenHash,
      Instant accessExpiresAt,
      int currentReportVersion,
      Instant createdAt,
      Instant updatedAt) {}

  public enum PurchaseMethod { CASH, FINANCE }

  public enum CaseStatus { DRAFT, INTAKE_IN_PROGRESS, NEEDS_REVIEW, READY_TO_ANALYZE, ANALYZING, COMPLETE, PARTIAL, FAILED }

  public record OperationView(
      String id,
      String caseId,
      OperationStage stage,
      String error,
      Instant createdAt,
      Instant updatedAt) {}
}
