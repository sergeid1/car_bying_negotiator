package com.dealanalyzer.verification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "deal_term_corrections")
class DealTermCorrectionEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(name = "target_type", nullable = false, length = 20) String targetType;
  @Column(name = "target_id", nullable = false, length = 36) String targetId;
  @Column(name = "correction_version", nullable = false) int correctionVersion;
  @Column(name = "idempotency_key", nullable = false, length = 200) String idempotencyKey;
  @Column(name = "request_fingerprint", nullable = false, length = 64) String requestFingerprint;
  @Column(name = "normalized_value", length = 500) String normalizedValue;
  @Column(name = "normalized_unit", length = 32) String normalizedUnit;
  @Column(name = "normalized_category", length = 64) String normalizedCategory;
  @Column(name = "amount_minor") Long amountMinor;
  @Column(nullable = false, length = 32) String actor;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected DealTermCorrectionEntity() {}

  DealTermCorrectionEntity(
      String id, String caseId, String extractionId, String targetType, String targetId,
      int version, String idempotencyKey, String requestFingerprint, String normalizedValue,
      String normalizedUnit, String normalizedCategory, Long amountMinor, Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.extractionId = extractionId;
    this.targetType = targetType;
    this.targetId = targetId;
    this.correctionVersion = version;
    this.idempotencyKey = idempotencyKey;
    this.requestFingerprint = requestFingerprint;
    this.normalizedValue = normalizedValue;
    this.normalizedUnit = normalizedUnit;
    this.normalizedCategory = normalizedCategory;
    this.amountMinor = amountMinor;
    this.actor = "BUYER";
    this.createdAt = now;
  }
}
