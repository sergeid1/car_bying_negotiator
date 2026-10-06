package com.dealanalyzer.mandate;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

final class MandateEntities {
  private MandateEntities() {}

  @Entity(name = "MandateEntity")
  @Immutable
  @Table(name = "negotiation_mandates")
  static class MandateEntity {
    @Id @Column(length = 36) String id;
    @Column(name = "case_id", nullable = false, updatable = false) String caseId;
    @Column(nullable = false, updatable = false) int version;
    @Column(name = "plan_snapshot_id", nullable = false, updatable = false) String planSnapshotId;
    @Column(name = "plan_version", nullable = false, updatable = false) int planVersion;
    @Column(nullable = false, updatable = false) String currency;
    @Column(name = "target_minor", nullable = false, updatable = false) long targetMinor;
    @Column(name = "ceiling_minor", nullable = false, updatable = false) long ceilingMinor;
    @Column(name = "plan_target_minor", nullable = false, updatable = false) long planTargetMinor;
    @Column(name = "plan_ceiling_minor", nullable = false, updatable = false) long planCeilingMinor;
    @Column(nullable = false, updatable = false) String disclosures;
    @Column(name = "expires_at", nullable = false, updatable = false) Instant expiresAt;
    @Column(name = "approved_by", nullable = false, updatable = false) String approvedBy;
    @Column(name = "approved_at", nullable = false, updatable = false) Instant approvedAt;
    @Column(name = "idempotency_key", nullable = false, updatable = false) String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, updatable = false) String requestFingerprint;
    protected MandateEntity() {}
    MandateEntity(String id, String caseId, int version, String planSnapshotId, int planVersion,
        MandatePolicy.Terms terms, String key, String fingerprint, Instant now) {
      this.id = id; this.caseId = caseId; this.version = version;
      this.planSnapshotId = planSnapshotId; this.planVersion = planVersion;
      this.currency = terms.currency(); this.targetMinor = terms.targetMinor();
      this.ceilingMinor = terms.ceilingMinor(); this.planTargetMinor = terms.planTargetMinor();
      this.planCeilingMinor = terms.planCeilingMinor();
      this.disclosures = String.join(",", terms.disclosures().stream().map(Enum::name).toList());
      this.expiresAt = terms.expiresAt();
      this.approvedBy = "BUYER"; this.approvedAt = now;
      this.idempotencyKey = key; this.requestFingerprint = fingerprint;
    }
  }

  @Entity(name = "RevocationEntity")
  @Immutable
  @Table(name = "negotiation_mandate_revocations")
  static class RevocationEntity {
    @Id @Column(name = "mandate_id", length = 36) String mandateId;
    @Column(name = "case_id", nullable = false, updatable = false) String caseId;
    @Column(name = "idempotency_key", nullable = false, updatable = false) String idempotencyKey;
    @Column(name = "revoked_by", nullable = false, updatable = false) String revokedBy;
    @Column(name = "revoked_at", nullable = false, updatable = false) Instant revokedAt;
    protected RevocationEntity() {}
    RevocationEntity(String mandateId, String caseId, String key, Instant now) {
      this.mandateId = mandateId; this.caseId = caseId; this.idempotencyKey = key;
      this.revokedBy = "BUYER"; this.revokedAt = now;
    }
  }
}
