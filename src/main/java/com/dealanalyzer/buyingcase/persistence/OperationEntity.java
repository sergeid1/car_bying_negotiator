package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "operations")
class OperationEntity {
  @Id
  @Column(length = 36)
  String id;

  // Scalar aggregate reference: operations never fetch or cascade an AnalysisCase entity.
  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(name = "idempotency_key", length = 200)
  String idempotencyKey;

  @Column(nullable = false, length = 40)
  String stage;

  @Column(name = "error_message", length = 500)
  String errorMessage;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected OperationEntity() {}

  OperationEntity(
      String id, String caseId, String idempotencyKey, String stage, Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.idempotencyKey = idempotencyKey;
    this.stage = stage;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
