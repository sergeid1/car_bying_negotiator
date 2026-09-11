package com.dealanalyzer.pilot;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Code-owned vocabulary only. No caller text, identifiers from providers, money, or document values. */
@Service
public class PilotEvents {
  public enum Type { REPORT_COMPLETED, REVIEW_STATUS, MARKET_COVERAGE, FINDING_FEEDBACK, OUTCOME_FEEDBACK }
  public enum Category { COMPLETE, PARTIAL, MATCHES_APPROVED_BASELINE, REVIEW_REQUIRED, UNABLE_TO_VERIFY,
    AVAILABLE, UNAVAILABLE, RECEIVED }
  private final PilotEventRepository events;
  public PilotEvents(PilotEventRepository events) { this.events = events; }
  @Transactional
  public void record(String caseId, Type type, Category category, Long durationMillis) {
    var row = new PilotEventEntity(); row.id = UUID.randomUUID().toString(); row.caseId = caseId;
    row.eventType = type.name(); row.category = category.name(); row.durationMillis = durationMillis;
    row.createdAt = Instant.now(); events.save(row);
  }
}
