package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@org.hibernate.annotations.Immutable
@Table(name = "analysis_reports")
class AnalysisReportEntity {
  @Column(name = "policy_metadata", nullable = false, updatable = false, columnDefinition = "text")
  String policyMetadata;

  @Column(name = "operation_id", updatable = false, length = 36)
  String operationId;
  @EmbeddedId
  AnalysisReportKey id;

  // Reports are append-only and hold only the aggregate identifier; no update cascade exists.
  @Column(name = "payload_json", nullable = false, updatable = false)
  String payloadJson;

  @Column(name = "analysis_fingerprint", nullable = false, length = 64, updatable = false)
  String analysisFingerprint;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  protected AnalysisReportEntity() {}

  AnalysisReportEntity(
      String caseId,
      int reportVersion,
      String payloadJson,
      String analysisFingerprint,
      Instant createdAt) {
    this(caseId, reportVersion, payloadJson, analysisFingerprint, createdAt, null);
  }

  AnalysisReportEntity(String caseId, int reportVersion, String payloadJson,
      String analysisFingerprint, Instant createdAt, String operationId) {
    this.id = new AnalysisReportKey(caseId, reportVersion);
    this.payloadJson = payloadJson;
    this.policyMetadata = com.dealanalyzer.operations.ReportPolicyMetadata.snapshot(payloadJson);
    this.analysisFingerprint = analysisFingerprint;
    this.createdAt = createdAt;
    this.operationId = operationId;
  }
}
