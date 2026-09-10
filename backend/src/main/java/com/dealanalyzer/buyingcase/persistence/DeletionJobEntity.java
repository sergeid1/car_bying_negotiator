package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "case_deletion_jobs")
class DeletionJobEntity {
  @Id String id;
  @Column(name = "case_id", nullable = false, unique = true) String caseId;
  @Column(name = "receipt_hash") String receiptHash;
  String state;
  String reason;
  @Column(name = "requested_at") Instant requestedAt;
  @Column(name = "last_attempt_at") Instant lastAttemptAt;
  @Column(name = "completed_at") Instant completedAt;
  int attempts;
  @Column(name = "failure_code") String failureCode;
  @Column(name = "removed_rows") long removedRows;
  @Column(name = "graph_duration_ms") long graphDurationMs;
  String verification;
  @Column(name = "backup_status") String backupStatus;
  @Column(name = "backup_expires_at") Instant backupExpiresAt;
  @Version @Column(name = "lock_version") long lockVersion;
}
