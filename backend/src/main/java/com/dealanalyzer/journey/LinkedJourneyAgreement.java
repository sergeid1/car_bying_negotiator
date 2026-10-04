package com.dealanalyzer.journey;

import com.dealanalyzer.quote.JourneyAgreement;
import com.dealanalyzer.quote.NegotiationStateService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies written quote agreement to the journey within the accepting case's transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LinkedJourneyAgreement implements JourneyAgreement {
  private final JdbcTemplate jdbc;
  private final BuyingJourneyRepository journeys;
  private final NegotiationStateService negotiations;

  LinkedJourneyAgreement(JdbcTemplate jdbc, BuyingJourneyRepository journeys, NegotiationStateService negotiations) {
    this.jdbc = jdbc; this.journeys = journeys; this.negotiations = negotiations;
  }

  @Override public Result agree(String caseId) {
    var linked = lock(caseId);
    if (linked.isEmpty()) return new Result(false, List.of());
    var journey = linked.orElseThrow();
    if (journey.state != JourneyState.AGREED) {
      journey.state = JourneyState.AGREED;
      journey.updatedAt = Instant.now();
    }
    return options(caseId, journey);
  }

  @Override public Result current(String caseId, Result recorded) {
    return lock(caseId).map(journey -> options(caseId, journey))
        .orElseGet(() -> new Result(false, List.of()));
  }

  @Override public Result choose(String acceptedCaseId, String otherCaseId, Action action) {
    var journey = lock(acceptedCaseId).orElseThrow(LinkedJourneyAgreement::invalidChoice);
    if (journey.state != JourneyState.AGREED || action == null || acceptedCaseId.equals(otherCaseId)
        || options(acceptedCaseId, journey).otherOpenNegotiations().stream()
            .noneMatch(other -> other.caseId().equals(otherCaseId))) throw invalidChoice();
    // An accepting case already holds its own lock before entering the journey. Waiting on
    // another case here would invert that order against a concurrent agreement in this journey.
    try {
      jdbc.queryForList("select id from analysis_cases where id = ? for update nowait", String.class, otherCaseId);
    } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
      throw new IllegalArgumentException("That negotiation is changing. Reload the journey and try again.");
    }
    if (options(acceptedCaseId, journey).otherOpenNegotiations().stream()
        .noneMatch(other -> other.caseId().equals(otherCaseId))) throw invalidChoice();
    negotiations.transition(otherCaseId, action == Action.PAUSE
        ? NegotiationStateService.State.PAUSED : NegotiationStateService.State.STOPPED);
    return options(acceptedCaseId, journey);
  }

  private Optional<BuyingJourneyEntity> lock(String caseId) {
    var before = link(caseId);
    if (before.isEmpty()) return Optional.empty();
    var link = before.orElseThrow();
    var journey = journeys.findLockedByIdAndAccountId(link.journeyId(), link.accountId())
        .orElseThrow(LinkedJourneyAgreement::invalidChoice);
    // Membership and case ownership may have changed while the journey lock was acquired.
    if (!link(caseId).filter(link::equals).isPresent()) throw invalidChoice();
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

  private Result options(String acceptedCaseId, BuyingJourneyEntity journey) {
    List<String> cases = jdbc.queryForList("""
        select l.case_id from journey_candidate_cases l
        join buyer_account_cases a on a.case_id = l.case_id and a.account_id = l.account_id
        join analysis_cases c on c.id = l.case_id and c.lifecycle = 'ACTIVE' and c.access_expires_at > CURRENT_TIMESTAMP
        join negotiation_mandates m on m.case_id = l.case_id
          and m.version = (select max(latest.version) from negotiation_mandates latest where latest.case_id = l.case_id)
          and m.expires_at > CURRENT_TIMESTAMP
        join price_plan_snapshots p on p.id = m.plan_snapshot_id and p.case_id = m.case_id and p.kind = 'PLAN'
          and p.version = (select max(latest.version) from price_plan_snapshots latest
            where latest.case_id = m.case_id and latest.kind = 'PLAN')
        left join case_negotiation_states s on s.case_id = l.case_id
        where l.journey_id = ? and l.account_id = ? and l.case_id <> ?
          and not exists (select 1 from negotiation_mandate_revocations r where r.mandate_id = m.id)
          and (s.state is null or s.state <> 'AGREED')
          and (s.mandate_id is null or s.mandate_id <> m.id
            or s.state in ('MANDATE_ACTIVE', 'AWAITING_DEALER', 'AWAITING_BUYER', 'PAUSED'))
        order by l.created_at, l.case_id
        """, String.class, journey.id, journey.accountId, acceptedCaseId);
    return new Result(true, cases.stream()
        .map(id -> new OtherNegotiation(id, "Buying Case " + id)).toList());
  }

  private static IllegalArgumentException invalidChoice() {
    return new IllegalArgumentException("Choose an open negotiation in this agreed journey.");
  }

  private record Link(String journeyId, String accountId) {}
}
