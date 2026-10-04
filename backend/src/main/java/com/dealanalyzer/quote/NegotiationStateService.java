package com.dealanalyzer.quote;

import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.mandate.MandateService;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NegotiationStateService {
  public enum State { NO_MANDATE, MANDATE_ACTIVE, AWAITING_DEALER, AWAITING_BUYER, AGREED, STOPPED, EXPIRED }
  private final JdbcTemplate jdbc;
  private final MandateService mandates;
  private final EphemeralCasePersistence locks;
  public NegotiationStateService(JdbcTemplate jdbc, MandateService mandates, EphemeralCasePersistence locks) {
    this.jdbc = jdbc; this.mandates = mandates; this.locks = locks;
  }
  @Transactional
  public State current(String caseId) {
    locks.lock(caseId);
    var rows = jdbc.query("select mandate_id, state from case_negotiation_states where case_id = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId);
    var active = mandates.activeMandate(caseId);
    if (active.isEmpty()) {
      var latest = jdbc.query("select id, expires_at from negotiation_mandates where case_id = ? order by version desc limit 1",
          (r, n) -> r.getTimestamp(2).toInstant(), caseId);
      State state = latest.isEmpty() ? State.NO_MANDATE : !latest.getFirst().isAfter(Instant.now()) ? State.EXPIRED : State.STOPPED;
      save(caseId, null, state); return state;
    }
    String id = active.orElseThrow().id();
    if (rows.isEmpty() || !id.equals(rows.getFirst()[0])) {
      save(caseId, id, State.MANDATE_ACTIVE); return State.MANDATE_ACTIVE;
    }
    return State.valueOf(rows.getFirst()[1]);
  }
  @Transactional
  public void transition(String caseId, State next) {
    State before = current(caseId);
    if (before == State.NO_MANDATE || before == State.EXPIRED || before == State.STOPPED || before == State.AGREED)
      throw new IllegalArgumentException("Negotiation is not active.");
    if (next == State.NO_MANDATE || next == State.MANDATE_ACTIVE || next == State.EXPIRED)
      throw new IllegalArgumentException("This state is determined by the mandate.");
    save(caseId, mandates.activeMandate(caseId).orElseThrow().id(), next);
  }
  @Transactional
  public void dealerReply(String caseId) {
    if (current(caseId) == State.AWAITING_DEALER) transition(caseId, State.AWAITING_BUYER);
  }
  private void save(String caseId, String mandate, State state) {
    Timestamp now = Timestamp.from(Instant.now());
    if (jdbc.update("update case_negotiation_states set mandate_id = ?, state = ?, updated_at = ? where case_id = ?",
        mandate, state.name(), now, caseId) == 0)
      jdbc.update("insert into case_negotiation_states(case_id, mandate_id, state, updated_at) values (?, ?, ?, ?)", caseId, mandate, state.name(), now);
  }
}
