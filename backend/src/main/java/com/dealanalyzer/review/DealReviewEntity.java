package com.dealanalyzer.review;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Scalar foreign keys deliberately expose no ORM update or persist cascades. */
@Entity
@Immutable
@Table(name = "deal_reviews")
class DealReviewEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, updatable = false) String caseId;
  @Column(nullable = false, updatable = false) int version;
  @Column(name = "baseline_id", updatable = false) String baselineId;
  @Column(name = "previous_offer_id", nullable = false, updatable = false) String previousOfferId;
  @Column(name = "current_offer_id", nullable = false, updatable = false) String currentOfferId;
  @Column(name = "tolerance_policy", nullable = false, updatable = false, length = 300) String tolerancePolicy;
  @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text") String payloadJson;
  @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
  protected DealReviewEntity() {}
  DealReviewEntity(String id, String caseId, int version, String baselineId, String previousOfferId,
      String currentOfferId, String payloadJson, Instant now) {
    this.id = id; this.caseId = caseId; this.version = version; this.baselineId = baselineId;
    this.previousOfferId = previousOfferId; this.currentOfferId = currentOfferId;
    this.tolerancePolicy = RevisedOfferPolicy.TOLERANCE; this.payloadJson = payloadJson; this.createdAt = now;
  }
}
