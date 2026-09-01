package com.dealanalyzer.document.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "document_operation_retries",
    uniqueConstraints = @UniqueConstraint(columnNames = {"operation_id", "idempotency_key"}))
class DocumentOperationRetryEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "operation_id", nullable = false, length = 36)
  String operationId;

  @Column(name = "idempotency_key", nullable = false, length = 200)
  String idempotencyKey;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected DocumentOperationRetryEntity() {}

  DocumentOperationRetryEntity(String id, String operationId, String idempotencyKey, Instant now) {
    this.id = id;
    this.operationId = operationId;
    this.idempotencyKey = idempotencyKey;
    this.createdAt = now;
  }
}
