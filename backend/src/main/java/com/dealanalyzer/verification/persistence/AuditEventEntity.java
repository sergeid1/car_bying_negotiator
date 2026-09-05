package com.dealanalyzer.verification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@org.hibernate.annotations.Immutable
@Table(name = "audit_events")
public class AuditEventEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(name = "event_type", nullable = false, length = 64) String eventType;
  @Column(name = "aggregate_type", nullable = false, length = 32) String aggregateType;
  @Column(name = "aggregate_id", nullable = false, length = 36) String aggregateId;
  @Column(name = "aggregate_version", nullable = false) int aggregateVersion;
  @Column(name = "idempotency_key", nullable = false, length = 200) String idempotencyKey;
  @Column(nullable = false, length = 32) String actor;
  @Column(name = "metadata_json", nullable = false, columnDefinition = "text") String metadataJson;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected AuditEventEntity() {}

  public AuditEventEntity(String id, String caseId, String eventType, String aggregateType,
      String aggregateId, int aggregateVersion, String idempotencyKey, String metadataJson,
      Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.eventType = eventType;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.aggregateVersion = aggregateVersion;
    this.idempotencyKey = idempotencyKey;
    this.actor = "BUYER";
    this.metadataJson = metadataJson;
    this.createdAt = now;
  }
}
