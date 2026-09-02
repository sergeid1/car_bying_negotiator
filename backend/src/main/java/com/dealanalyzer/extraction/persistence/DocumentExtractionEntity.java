package com.dealanalyzer.extraction.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "document_extractions")
class DocumentExtractionEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "document_id", nullable = false, length = 36) String documentId;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(name = "extraction_version", nullable = false) int extractionVersion;
  @Column(nullable = false, length = 32) String status;
  @Column(name = "extractor_version", nullable = false, length = 100) String extractorVersion;
  @Column(name = "model_version", nullable = false, length = 100) String modelVersion;
  @Column(name = "schema_version", nullable = false, length = 100) String schemaVersion;
  @Column(name = "overall_confidence", nullable = false, precision = 6, scale = 5) BigDecimal overallConfidence;
  @Column(name = "suspected_missing_pages", nullable = false) boolean suspectedMissingPages;
  @Column(name = "document_warnings", nullable = false, columnDefinition = "text") String documentWarnings;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected DocumentExtractionEntity() {}

  DocumentExtractionEntity(
      String id, String documentId, String caseId, int version, String status,
      String extractorVersion, String modelVersion, String schemaVersion,
      BigDecimal confidence, boolean suspectedMissingPages, String documentWarnings, Instant now) {
    this.id = id;
    this.documentId = documentId;
    this.caseId = caseId;
    this.extractionVersion = version;
    this.status = status;
    this.extractorVersion = extractorVersion;
    this.modelVersion = modelVersion;
    this.schemaVersion = schemaVersion;
    this.overallConfidence = confidence;
    this.suspectedMissingPages = suspectedMissingPages;
    this.documentWarnings = documentWarnings;
    this.createdAt = now;
  }
}
