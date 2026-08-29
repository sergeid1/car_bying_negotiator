package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "consent_records")
class ConsentRecordEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(nullable = false, length = 40)
  String purpose;

  @Column(name = "consent_version", nullable = false)
  int consentVersion;

  @Column(nullable = false, length = 16)
  String action;

  @Column(name = "policy_version", nullable = false, length = 100)
  String policyVersion;

  @Column(name = "occurred_at", nullable = false)
  Instant occurredAt;

  protected ConsentRecordEntity() {}

  ConsentRecordEntity(
      String id,
      String caseId,
      String purpose,
      int consentVersion,
      String action,
      String policyVersion,
      Instant occurredAt) {
    this.id = id;
    this.caseId = caseId;
    this.purpose = purpose;
    this.consentVersion = consentVersion;
    this.action = action;
    this.policyVersion = policyVersion;
    this.occurredAt = occurredAt;
  }
}
