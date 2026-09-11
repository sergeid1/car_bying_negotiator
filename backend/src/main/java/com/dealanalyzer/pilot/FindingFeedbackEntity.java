package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "finding_feedback")
class FindingFeedbackEntity {
  @Id @Column(length = 36) String id;
  @Column(nullable = false, length = 36) String caseId;
  int reportVersion;
  @Column(nullable = false, length = 160) String findingPointer;
  @Column(nullable = false, length = 10) String accurate;
  @Column(nullable = false, length = 10) String understandable;
  @Column(nullable = false, length = 10) String useful;
  @Column(nullable = false) Instant createdAt;
}
