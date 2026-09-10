package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "case_deletion_attempts")
class DeletionAttemptEntity {
  @Id String id;
  @Column(name = "job_id") String jobId;
  @Column(name = "started_at") Instant startedAt;
  @Column(name = "finished_at") Instant finishedAt;
  String outcome;
  @Column(name = "verified_participants") int verifiedParticipants;
  @Column(name = "failure_code") String failureCode;
}
