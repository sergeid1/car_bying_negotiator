package com.dealanalyzer.journey;

import com.dealanalyzer.appointment.JourneyAppointment;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies test-drive booking and cancellation to the journey within the case's transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LinkedJourneyAppointment implements JourneyAppointment {
  private final JdbcTemplate jdbc;
  private final BuyingJourneyRepository journeys;

  LinkedJourneyAppointment(JdbcTemplate jdbc, BuyingJourneyRepository journeys) {
    this.jdbc = jdbc; this.journeys = journeys;
  }

  @Override public boolean scheduled(String caseId) {
    return move(caseId, JourneyState.AGREED, JourneyState.TEST_DRIVE_SCHEDULED);
  }

  @Override public boolean cancelled(String caseId) {
    return move(caseId, JourneyState.TEST_DRIVE_SCHEDULED, JourneyState.AGREED);
  }

  private boolean move(String caseId, JourneyState from, JourneyState to) {
    var linked = lock(caseId);
    if (linked.isEmpty()) return false;
    var journey = linked.orElseThrow();
    if (journey.state == from) {
      journey.state = to;
      journey.updatedAt = Instant.now();
    }
    return true;
  }

  // Mirrors LinkedJourneyAgreement: the case lock is held, then the journey lock, then membership is rechecked.
  private Optional<BuyingJourneyEntity> lock(String caseId) {
    var before = link(caseId);
    if (before.isEmpty()) return Optional.empty();
    var link = before.orElseThrow();
    var journey = journeys.findLockedByIdAndAccountId(link.journeyId(), link.accountId())
        .orElseThrow(LinkedJourneyAppointment::changed);
    if (!link(caseId).filter(link::equals).isPresent()) throw changed();
    return Optional.of(journey);
  }

  private Optional<Link> link(String caseId) {
    return jdbc.query("""
        select l.journey_id, l.account_id from journey_candidate_cases l
        join buyer_account_cases a on a.case_id = l.case_id and a.account_id = l.account_id
        join buyer_accounts b on b.id = l.account_id and b.lifecycle = 'ACTIVE'
        join analysis_cases c on c.id = l.case_id and c.lifecycle = 'ACTIVE' and c.access_expires_at > CURRENT_TIMESTAMP
        where l.case_id = ?
        """, (row, n) -> new Link(row.getString(1), row.getString(2)), caseId).stream().findFirst();
  }

  private static IllegalArgumentException changed() {
    return new IllegalArgumentException("Your journey changed. Reload it and try again.");
  }

  private record Link(String journeyId, String accountId) {}
}
