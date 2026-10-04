package com.dealanalyzer.purchase;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Global aggregate evidence only; no association to the Buyer case graph. */
@Entity
@Immutable
@Table(name = "contribution_decision_records")
class ContributionDecisionRecord {
  @Id @Column(length = 36, updatable = false) String id;
  @Column(name = "window_from", nullable = false, updatable = false) LocalDate windowFrom;
  @Column(name = "window_to", nullable = false, updatable = false) LocalDate windowTo;
  @Column(name = "record_kind", nullable = false, updatable = false, length = 10) String kind;
  @Column(nullable = false, updatable = false) int version;
  @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text") String payload;
  @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
  protected ContributionDecisionRecord() {}
  ContributionDecisionRecord(LocalDate from, LocalDate to, String kind, int version, String payload) {
    this.id = UUID.randomUUID().toString(); this.windowFrom = from; this.windowTo = to;
    this.kind = kind; this.version = version; this.payload = payload; this.createdAt = Instant.now();
  }
}
