package com.dealanalyzer.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Called under the conversation's case lock and transaction. Audit history is the durable decision. */
@Service
public class DealerDeclinedService {
  public enum Source { BUYER, RULE }
  public record Decision(Source source, int rounds, Long lastVerifiedSellingPriceMinor, String currency) {}
  private record Stored(Decision decision, int offerVersion) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final int rounds;

  public DealerDeclinedService(JdbcTemplate jdbc, ObjectMapper json,
      @Value("${negotiation.dealer-declined.rounds:2}") int rounds) {
    if (rounds < 1) throw new IllegalArgumentException("Dealer-declined rounds must be at least one.");
    this.jdbc = jdbc; this.json = json; this.rounds = rounds;
  }

  public Decision current(String caseId) {
    List<DealerDeclinedRule.Offer> offers = offers(caseId);
    Stored stored = stored(caseId);
    if (stored != null && stored.decision().source() == Source.RULE && loweredSince(offers, stored.offerVersion())) {
      audit(caseId, "DEALER_DECLINED_CLEARED", stored, "RULE");
      stored = null;
    }
    if (stored == null && DealerDeclinedRule.holds(offers, sent(caseId), rounds)) {
      stored = snapshot(Source.RULE, rounds, offers);
      audit(caseId, "DEALER_DECLINED_RECORDED", stored, "RULE");
    }
    if (stored == null) return null;
    // Keep the recorded threshold, while showing the latest verified price to the Buyer.
    Decision result = snapshot(stored.decision().source(), stored.decision().rounds(), offers).decision();
    jdbc.update("""
        update negotiation_drafts set state = 'CANCELLED', cancel_reason = 'DEALER_DECLINED', decided_at = ?
        where case_id = ? and state = 'PROPOSED'
        """, Timestamp.from(com.dealanalyzer.time.PersistedInstant.round(Instant.now())), caseId);
    return result;
  }

  public void recordBuyer(String caseId) {
    Stored previous = stored(caseId);
    if (previous != null && previous.decision().source() == Source.BUYER) return;
    audit(caseId, "DEALER_DECLINED_RECORDED", snapshot(Source.BUYER, rounds, offers(caseId)), "BUYER");
  }

  public void clearBuyer(String caseId) {
    Stored previous = stored(caseId);
    if (previous != null && previous.decision().source() == Source.BUYER)
      audit(caseId, "DEALER_DECLINED_CLEARED", previous, "BUYER");
  }

  private boolean loweredSince(List<DealerDeclinedRule.Offer> offers, int version) {
    for (int i = 1; i < offers.size(); i++)
      if (offers.get(i).version() > version && DealerDeclinedRule.lower(offers.get(i), offers.get(i - 1))) return true;
    return false;
  }

  private Stored snapshot(Source source, int threshold, List<DealerDeclinedRule.Offer> offers) {
    var last = offers.isEmpty() ? null : offers.getLast();
    return new Stored(new Decision(source, threshold, last == null ? null : last.sellingPriceMinor(),
        last == null ? null : last.currency()), last == null ? 0 : last.version());
  }

  private List<DealerDeclinedRule.Offer> offers(String caseId) {
    return jdbc.query("""
        select id, offer_version, terms_json, created_at from dealer_offers
        where case_id = ? and status = 'VERIFIED' order by offer_version
        """, (r, n) -> {
          JsonNode terms = read(r.getString(3));
          Long selling = WrittenQuoteEvidence.amount(terms, "SELLING_PRICE");
          // amount() accepts only Buyer-verified USD_MINOR candidates, including duplicate checks.
          return new DealerDeclinedRule.Offer(r.getString(1), r.getInt(2), selling,
              selling == null ? null : "USD", r.getTimestamp(4).toInstant());
        }, caseId);
  }

  private List<DealerDeclinedRule.Sent> sent(String caseId) {
    return jdbc.query("""
        select d.id, d.dealer_offer_id, m.created_at from negotiation_drafts d
        join dealer_relay_messages m on m.id = d.message_id and m.case_id = d.case_id
        where d.case_id = ? and d.state = 'SENT' order by m.created_at, d.id
        """, (r, n) -> new DealerDeclinedRule.Sent(r.getString(1), r.getString(2), r.getTimestamp(3).toInstant()), caseId);
  }

  private Stored stored(String caseId) {
    var rows = jdbc.query("""
        select event_type, metadata_json from audit_events where case_id = ? and aggregate_type = 'DEALER_DECLINED'
        order by aggregate_version desc limit 1
        """, (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId);
    if (rows.isEmpty() || "DEALER_DECLINED_CLEARED".equals(rows.getFirst()[0])) return null;
    try { return json.readValue(rows.getFirst()[1], Stored.class); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read dealer-declined decision", failure); }
  }

  private void audit(String caseId, String event, Stored stored, String actor) {
    int version = jdbc.queryForObject("""
        select coalesce(max(aggregate_version), 0) + 1 from audit_events
        where case_id = ? and aggregate_type = 'DEALER_DECLINED'
        """, Integer.class, caseId);
    try {
      jdbc.update("""
          insert into audit_events(id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version, idempotency_key, actor, metadata_json, created_at)
          values (?, ?, ?, 'DEALER_DECLINED', ?, ?, ?, ?, ?, ?)
          """, UUID.randomUUID().toString(), caseId, event, caseId, version, "dealer-declined:" + version, actor, json.writeValueAsString(stored),
          Timestamp.from(com.dealanalyzer.time.PersistedInstant.round(Instant.now())));
    } catch (java.io.IOException failure) { throw new IllegalStateException("Unable to record dealer-declined decision", failure); }
  }

  private JsonNode read(String value) {
    try { return json.readTree(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Unable to read verified offer", failure); }
  }
}
