package com.dealanalyzer.verification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@org.hibernate.annotations.Immutable
@Entity
@Table(name = "dealer_offers")
public class DealerOfferEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(name = "offer_version", nullable = false) int offerVersion;
  @Column(name = "idempotency_key", nullable = false, length = 200) String idempotencyKey;
  @Column(name = "request_fingerprint", nullable = false, length = 64) String requestFingerprint;
  @Column(nullable = false, length = 20) String status;
  @Column(name = "schema_version", nullable = false, length = 100) String schemaVersion;
  @Column(name = "terms_json", nullable = false, columnDefinition = "text") String termsJson;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  @Column(name = "previous_offer_id", updatable = false) String previousOfferId;

  protected DealerOfferEntity() {}

  public String id() {
    return id;
  }

  DealerOfferEntity(String id, String caseId, String extractionId, int version,
      String idempotencyKey, String requestFingerprint, String termsJson, String previousOfferId, Instant now) {
    this.previousOfferId = previousOfferId;
    this.id = id;
    this.caseId = caseId;
    this.extractionId = extractionId;
    this.offerVersion = version;
    this.idempotencyKey = idempotencyKey;
    this.requestFingerprint = requestFingerprint;
    this.status = "VERIFIED";
    this.schemaVersion = "dealer-offer.v1";
    this.termsJson = termsJson;
    this.createdAt = now;
  }
}
