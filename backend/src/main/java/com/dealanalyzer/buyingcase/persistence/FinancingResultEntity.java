package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "financing_results")
class FinancingResultEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(nullable = false) int version;
  @Column(name = "finance_offer_id", nullable = false, length = 36) String financeOfferId;
  @Column(name = "policy_version", nullable = false, length = 100) String policyVersion;
  @Column(name = "payload_json", nullable = false, columnDefinition = "text") String payloadJson;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected FinancingResultEntity() {}
}
