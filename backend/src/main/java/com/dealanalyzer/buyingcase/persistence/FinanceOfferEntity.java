package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "finance_offers")
class FinanceOfferEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(nullable = false) int version;
  @Column(name = "dealer_offer_id", nullable = false, length = 36) String dealerOfferId;
  @Column(name = "idempotency_key", nullable = false, length = 200) String idempotencyKey;
  @Column(name = "request_fingerprint", nullable = false, length = 64) String requestFingerprint;
  @Column(name = "payload_json", nullable = false, columnDefinition = "text") String payloadJson;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected FinanceOfferEntity() {}
}
