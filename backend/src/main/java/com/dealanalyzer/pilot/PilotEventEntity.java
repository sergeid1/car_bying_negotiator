package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pilot_events")
class PilotEventEntity {
  @Id @Column(length = 36) String id;
  @Column(nullable = false, length = 36) String caseId;
  @Column(nullable = false, length = 32) String eventType;
  @Column(nullable = false, length = 40) String category;
  Long durationMillis;
  @Column(nullable = false) Instant createdAt;
}
