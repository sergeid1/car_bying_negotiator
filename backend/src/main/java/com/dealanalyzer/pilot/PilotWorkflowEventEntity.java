package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@org.hibernate.annotations.Immutable
@Table(name = "pilot_workflow_events")
class PilotWorkflowEventEntity {
  @Id @Column(length = 100) String id;
  @Column(length = 36) String caseId;
  @Column(length = 40) String eventType;
  @Column(length = 40) String category;
  Long durationMillis;
  Instant createdAt;
}
