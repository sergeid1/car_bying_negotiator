package com.dealanalyzer.economics.persistence;

import com.dealanalyzer.verification.persistence.DealerOfferEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cash_economics_results")
class CashEconomicsResultEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "dealer_offer_id", nullable = false) DealerOfferEntity dealerOffer;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "policy_version", nullable = false) JurisdictionPolicyEntity policy;
  @Column(name = "eligibility_fingerprint", nullable = false, length = 64)
  String eligibilityFingerprint;
  @Column(name = "schema_version", nullable = false, length = 100) String schemaVersion;
  @Column(name = "result_json", nullable = false, columnDefinition = "text") String resultJson;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected CashEconomicsResultEntity() {}

  CashEconomicsResultEntity(
      String id, String caseId, DealerOfferEntity dealerOffer, JurisdictionPolicyEntity policy,
      String eligibilityFingerprint, String resultJson, Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.dealerOffer = dealerOffer;
    this.policy = policy;
    this.eligibilityFingerprint = eligibilityFingerprint;
    this.schemaVersion = "cash-economics.v1";
    this.resultJson = resultJson;
    this.createdAt = createdAt;
  }
}
