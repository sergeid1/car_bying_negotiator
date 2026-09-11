package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pilot_observations")
class PilotObservationEntity {
  @Id @Column(length = 36) String id;
  @Column(nullable = false, length = 48) String metric;
  long numerator;
  long denominator;
  Long totalDeltaMinor;
  @Column(nullable = false, length = 32) String source;
  @Column(nullable = false, length = 64) String recordedBy;
  @Column(nullable = false) Instant createdAt;
}
