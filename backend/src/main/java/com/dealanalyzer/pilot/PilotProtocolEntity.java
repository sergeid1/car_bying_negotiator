package com.dealanalyzer.pilot;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pilot_protocol")
class PilotProtocolEntity {
  @Id Integer id;
  @Column(columnDefinition = "text") String protocolJson;
  Instant frozenAt;
  Instant reviewedAt;
  @Column(length = 64) String frozenBy;
}
