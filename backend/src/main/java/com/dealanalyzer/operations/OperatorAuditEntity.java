package com.dealanalyzer.operations;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "operator_audit")
class OperatorAuditEntity {
  @Id String id;
  @Column(name = "case_id") String caseId;
  @Column(name = "review_id") String reviewId;
  String actor;
  String action;
  @Column(name = "occurred_at") Instant occurredAt;
}
