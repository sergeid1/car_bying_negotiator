package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "experiment_assignments")
class ExperimentAssignmentEntity {
  @Id @Column(length = 36) String id;
  @Column(nullable = false, length = 36) String caseId;
  @Column(nullable = false, length = 1) String variant;
  long priceMinor;
  boolean qualified;
  @Column(nullable = false, length = 64) String enrolledBy;
  @Column(nullable = false) Instant createdAt;
}
