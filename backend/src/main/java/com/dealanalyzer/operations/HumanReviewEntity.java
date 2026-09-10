package com.dealanalyzer.operations;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "human_review_requests")
class HumanReviewEntity {
  @Id String id;
  @Column(name = "case_id") String caseId;
  @Column(name = "extraction_id") String extractionId;
  @Column(name = "candidate_id") String candidateId;
  @Column(name = "consent_version") int consentVersion;
  String state;
  String reviewer;
  @Column(name = "expires_at") Instant expiresAt;
  String outcome;
  @Column(name = "created_at") Instant createdAt;
}
