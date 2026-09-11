package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outcome_feedback")
class OutcomeFeedbackEntity {
  @Id @Column(length = 36) String id;
  @Column(nullable = false, length = 36) String caseId;
  @Column(nullable = false, length = 10) String stage;
  @Column(length = 24) String action;
  @Column(length = 10) String useful;
  @Column(length = 10) String trust;
  Boolean liveUse;
  Boolean understoodDecision;
  @Column(length = 24) String disappointment;
  Boolean willingToPay;
  @Column(nullable = false, columnDefinition = "text") String payloadJson;
  @Column(nullable = false) Instant createdAt;
}
