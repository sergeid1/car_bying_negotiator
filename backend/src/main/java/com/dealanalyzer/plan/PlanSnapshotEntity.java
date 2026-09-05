package com.dealanalyzer.plan;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "price_plan_snapshots")
class PlanSnapshotEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, updatable = false) String caseId;
  @Column(nullable = false, updatable = false) String kind;
  @Column(nullable = false, updatable = false) int version;
  @Column(name = "idempotency_key", nullable = false, updatable = false) String idempotencyKey;
  @Column(name = "request_fingerprint", nullable = false, updatable = false) String requestFingerprint;
  @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text") String payloadJson;
  @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
  protected PlanSnapshotEntity() {}
  PlanSnapshotEntity(String id, String caseId, String kind, int version, String key,
      String fingerprint, String payload, Instant now) {
    this.id = id; this.caseId = caseId; this.kind = kind; this.version = version;
    this.idempotencyKey = key; this.requestFingerprint = fingerprint;
    this.payloadJson = payload; this.createdAt = now;
  }
}
