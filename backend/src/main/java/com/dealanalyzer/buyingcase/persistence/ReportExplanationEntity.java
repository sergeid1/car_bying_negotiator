package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** The explanation served for one report version: written once, never updated, no associations. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "report_explanations")
class ReportExplanationEntity {
  @EmbeddedId AnalysisReportKey id;
  @Column(name = "policy_version", nullable = false, updatable = false) String policyVersion;
  @Column(name = "path", nullable = false, updatable = false) String path;
  @Column(name = "fallback_reason", updatable = false) String fallbackReason;
  @Column(name = "model_provider", updatable = false) String modelProvider;
  @Column(name = "model_id", updatable = false) String modelId;
  @Column(name = "prompt_version", updatable = false) String promptVersion;
  @Column(name = "schema_version", updatable = false) String schemaVersion;
  @Column(name = "threshold_set_version", updatable = false) String thresholdSetVersion;
  @Column(name = "evaluation_set_version", updatable = false) String evaluationSetVersion;
  @Column(name = "payload_json", nullable = false, updatable = false) String payloadJson;
  @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;

  protected ReportExplanationEntity() {}

  ReportExplanationEntity(AnalysisReportKey id, com.dealanalyzer.explanation.ReportExplanation explanation,
      String payloadJson, Instant createdAt) {
    this.id = id;
    this.policyVersion = explanation.policyVersion();
    this.path = explanation.path().name();
    this.fallbackReason = explanation.fallbackReason();
    var provenance = explanation.provenance();
    if (provenance != null) {
      this.modelProvider = provenance.provider().name();
      this.modelId = provenance.modelId();
      this.promptVersion = provenance.promptVersion();
      this.schemaVersion = provenance.schemaVersion();
      this.thresholdSetVersion = provenance.thresholdSetVersion();
      this.evaluationSetVersion = provenance.evaluationSetVersion();
    }
    this.payloadJson = payloadJson;
    this.createdAt = createdAt;
  }
}
