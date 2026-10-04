package com.dealanalyzer.journey;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable evidence captured from one matching run; only the Buyer's shortlist position changes. */
@Entity @Table(name = "journey_candidates")
class JourneyCandidateEntity {
  @Id String id;
  @Column(name = "journey_id", updatable = false) String journeyId;
  @Column(name = "account_id", updatable = false) String accountId;
  @Column(name = "brief_version", updatable = false) int briefVersion;
  @Column(name = "search_run_id", updatable = false) String searchRunId;
  @Column(name = "matcher_version", updatable = false) String matcherVersion;
  @Column(name = "candidate_json", updatable = false) String candidateJson;
  @Column(name = "listing_json", updatable = false) String listingJson;
  @Column(name = "market_json", updatable = false) String marketJson;
  @Column(name = "provider_version", updatable = false) String providerVersion;
  @Column(name = "shortlist_position") Integer shortlistPosition;
  @Column(name = "created_at", updatable = false) Instant createdAt;
  protected JourneyCandidateEntity() {}
}
