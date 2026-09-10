package com.dealanalyzer.operations;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Explicit scalar JPA queries: never materialize Buyer aggregates, documents, or report payloads. */
@Repository
@Transactional(readOnly = true)
public class OperationsProjection {
  private final EntityManager manager;
  public OperationsProjection(EntityManager manager) { this.manager = manager; }
  List<Map<String, Object>> rows(String query, Map<String, Object> parameters) {
    var q = manager.createQuery(query, Tuple.class).setMaxResults(100);
    parameters.forEach(q::setParameter);
    return q.getResultList().stream().map(tuple -> {
      Map<String, Object> row = new LinkedHashMap<>();
      tuple.getElements().forEach(element -> row.put(element.getAlias(), tuple.get(element)));
      return row;
    }).toList();
  }
  public Map<String, Object> diagnostics(String caseId) {
    var parameters = Map.<String, Object>of("id", caseId);
    var operations = rows("select o.id as correlationId, o.stage as stage, o.createdAt as createdAt, o.updatedAt as updatedAt, o.failureCategory as failureCategory from OperationEntity o where o.caseId = :id order by o.createdAt desc", parameters);
    var documents = rows("select o.id as correlationId, o.stage as stage, o.failedStage as failedStage, o.errorCode as errorCode, o.retryCount as retryCount, o.updatedAt as updatedAt from DocumentOperationEntity o where o.caseId = :id order by o.createdAt desc", parameters);
    documents.forEach(row -> {
      String category = documentFailure((String) row.remove("errorCode"));
      row.put("failureCategory", category);
      row.put("safeDetail", switch (category) {
        case "BUYER_INPUT" -> "Document needs Buyer attention.";
        case "CONTRACT_FAILURE" -> "Provider returned data outside the supported extraction contract.";
        case "PROVIDER_FAILURE" -> "Document dependency unavailable.";
        case "INTERNAL" -> "Processing failed; retry or investigate with the correlation identifier.";
        default -> "No failure recorded.";
      });
    });
    var reports = rows("select r.id.reportVersion as reportVersion, r.operationId as correlationId, r.policyMetadata as policies, r.createdAt as createdAt from AnalysisReportEntity r where r.id.caseId = :id order by r.id.reportVersion desc", parameters);
    reports.forEach(row -> row.put("policies", ReportPolicyMetadata.read((String) row.get("policies"))));
    return Map.of("operations", operations, "documents", documents, "reports", reports, "activePolicies", Map.of(
        "coreReview", com.dealanalyzer.report.CoreReviewPolicy.VERSION,
        "priceBoundaries", com.dealanalyzer.plan.PriceBoundaryPolicy.VERSION,
        "marketSelection", com.dealanalyzer.market.MarketAssessmentPolicy.VERSION));
  }
  private static String documentFailure(String code) {
    if (code == null) return "NONE";
    return switch (code) {
      case "MALWARE_DETECTED", "UNSUPPORTED_FILE_TYPE", "INVALID_DOCUMENT", "DOCUMENT_UNREADABLE", "UNREADABLE_DOCUMENT", "LIMIT_EXCEEDED" -> "BUYER_INPUT";
      case "CONTRACT_FAILURE" -> "CONTRACT_FAILURE";
      case "PROVIDER_FAILURE", "MALWARE_SCAN_UNAVAILABLE", "NORMALIZATION_TIMEOUT" -> "PROVIDER_FAILURE";
      default -> "INTERNAL";
    };
  }
  public List<Map<String, Object>> usage(String caseId) {
    return rows("select u.id as correlationId, u.operation as operation, u.provider as provider, u.providerVersion as providerVersion, u.modelVersion as modelVersion, u.schemaVersion as schemaVersion, u.license as license, u.unit as unit, u.units as units, u.costUsd as costUsd, u.status as status, u.createdAt as createdAt, u.finishedAt as finishedAt from ProviderUsageEntity u where u.caseId = :id order by u.createdAt desc", Map.of("id", caseId));
  }
  public List<Map<String, Object>> providers() {
    return rows("select u.operation as operation, u.provider as provider, u.providerVersion as providerVersion, u.status as status, count(u.id) as calls, sum(u.units) as units, sum(u.costUsd) as knownCostUsd, count(u.costUsd) as callsWithKnownCost, max(u.createdAt) as lastObservedAt from ProviderUsageEntity u group by u.operation, u.provider, u.providerVersion, u.status order by max(u.createdAt) desc", Map.of());
  }
  public List<Map<String, Object>> audit(String caseId) {
    var events = new java.util.ArrayList<>(rows("select a.id as id, a.reviewId as reviewId, a.actor as actor, a.action as action, a.occurredAt as occurredAt from OperatorAuditEntity a where a.caseId = :id order by a.occurredAt desc", Map.of("id", caseId)));
    events.addAll(rows("select a.id as id, a.actor as actor, a.eventType as action, a.aggregateType as aggregateType, a.aggregateId as aggregateId, a.aggregateVersion as aggregateVersion, a.createdAt as occurredAt from AuditEventEntity a where a.caseId = :id order by a.createdAt desc", Map.of("id", caseId)));
    events.sort(java.util.Comparator.comparing(row -> (java.time.Instant) row.get("occurredAt"), java.util.Comparator.reverseOrder()));
    return events.stream().limit(100).toList();
  }
  public Map<String, Object> deletion(String caseId) {
    var jobs = rows("select j.id as id, j.state as state, j.reason as reason, j.requestedAt as requestedAt, j.completedAt as completedAt, j.attempts as attempts, j.failureCode as failureCode, j.removedRows as removedRows, j.verification as verification, j.backupStatus as backupStatus, j.backupExpiresAt as backupExpiresAt from DeletionJobEntity j where j.caseId = :id", Map.of("id", caseId));
    if (jobs.isEmpty()) throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Deletion not found");
    var attempts = rows("select a.startedAt as startedAt, a.finishedAt as finishedAt, a.outcome as outcome, a.verifiedParticipants as verifiedParticipants, a.failureCode as failureCode from DeletionAttemptEntity a where a.jobId = :id order by a.startedAt", Map.of("id", jobs.getFirst().get("id")));
    return Map.of("job", jobs.getFirst(), "attempts", attempts);
  }
}
