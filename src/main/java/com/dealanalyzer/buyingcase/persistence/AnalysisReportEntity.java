package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "analysis_reports")
class AnalysisReportEntity {
  @EmbeddedId
  AnalysisReportKey id;

  // Reports are append-only and hold only the aggregate identifier; no update cascade exists.
  @Column(name = "payload_json", nullable = false)
  String payloadJson;

  @Column(name = "analysis_fingerprint", nullable = false, length = 64)
  String analysisFingerprint;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected AnalysisReportEntity() {}

  AnalysisReportEntity(
      String caseId,
      int reportVersion,
      String payloadJson,
      String analysisFingerprint,
      Instant createdAt) {
    this.id = new AnalysisReportKey(caseId, reportVersion);
    this.payloadJson = payloadJson;
    this.analysisFingerprint = analysisFingerprint;
    this.createdAt = createdAt;
  }
}
