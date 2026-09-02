package com.dealanalyzer.extraction.persistence;

import com.dealanalyzer.extraction.DealTermsValidator.ValidationIssue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "extraction_validation_issues")
class ExtractionValidationIssueEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(nullable = false, length = 16) String severity;
  @Column(name = "issue_code", nullable = false, length = 64) String issueCode;
  @Column(name = "field_key", length = 64) String fieldKey;
  @Column(nullable = false, length = 500) String message;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected ExtractionValidationIssueEntity() {}

  ExtractionValidationIssueEntity(String id, String extractionId, ValidationIssue value, Instant now) {
    this.id = id;
    this.extractionId = extractionId;
    this.severity = value.severity();
    this.issueCode = value.code();
    this.fieldKey = value.field();
    this.message = value.message();
    this.createdAt = now;
  }
}
