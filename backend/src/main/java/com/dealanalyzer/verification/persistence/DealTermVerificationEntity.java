package com.dealanalyzer.verification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "deal_term_verifications")
class DealTermVerificationEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(name = "dealer_offer_id", nullable = false, length = 36) String dealerOfferId;
  @Column(name = "target_type", nullable = false, length = 20) String targetType;
  @Column(name = "target_id", nullable = false, length = 36) String targetId;
  @Column(name = "verification_version", nullable = false) int verificationVersion;
  @Column(name = "effective_value", nullable = false, length = 500) String effectiveValue;
  @Column(nullable = false, length = 32) String actor;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected DealTermVerificationEntity() {}

  DealTermVerificationEntity(String id, String caseId, String extractionId, String dealerOfferId,
      String targetType, String targetId, int version, String effectiveValue, Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.extractionId = extractionId;
    this.dealerOfferId = dealerOfferId;
    this.targetType = targetType;
    this.targetId = targetId;
    this.verificationVersion = version;
    this.effectiveValue = effectiveValue;
    this.actor = "BUYER";
    this.createdAt = now;
  }
}
