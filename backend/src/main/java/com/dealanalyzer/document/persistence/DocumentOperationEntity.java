package com.dealanalyzer.document.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "document_processing_operations")
class DocumentOperationEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(name = "document_id", nullable = false, length = 36)
  String documentId;

  @Column(name = "idempotency_key", nullable = false, length = 200)
  String idempotencyKey;

  @Column(nullable = false, length = 40)
  String stage;

  @Column(name = "failed_stage", length = 40)
  String failedStage;

  @Column(name = "error_code", length = 64)
  String errorCode;

  @Column(name = "error_message", length = 500)
  String errorMessage;

  @Column(name = "retry_count", nullable = false)
  int retryCount;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected DocumentOperationEntity() {}

  DocumentOperationEntity(
      String id, String caseId, String documentId, String idempotencyKey, Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.documentId = documentId;
    this.idempotencyKey = idempotencyKey;
    this.stage = "QUEUED";
    this.createdAt = now;
    this.updatedAt = now;
  }
}
