package com.dealanalyzer.trade;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A journey's Trade Vehicle for the account export and journey deletion, inside the caller's transaction. */
@Component
public class TradeJourneyRecords {
  private static final String VEHICLE_COLUMNS = "id, vin, decode_outcome, decoder_source, decoder_version, decoded_at, "
      + "model_year, make, model, trim_name, mileage, condition, money_owed, lender, payoff_minor, payoff_good_through, "
      + "revision, changed_at, created_at";
  private static final String OFFER_COLUMNS = "id, evidence_document_id, amount_minor, source, offer_date, expires_on, created_at";
  private static final String ESTIMATE_COLUMNS = "id, source, low_minor, high_minor, checked_on, created_at";

  private final EntityManager manager;

  TradeJourneyRecords(EntityManager manager) { this.manager = manager; }

  /** The journey's Trade Vehicle with its written offers, the Buyer's estimates and the Desk Check cases that joined it, or null. */
  public Map<String, Object> exportForJourney(String journeyId) {
    var vehicles = rows("select " + VEHICLE_COLUMNS + " from trade_vehicles where journey_id = :id", VEHICLE_COLUMNS, journeyId);
    if (vehicles.isEmpty()) return null;
    var trade = vehicles.getFirst();
    trade.put("offers", rows("select " + OFFER_COLUMNS + " from trade_offers where trade_id = :id order by created_at, id",
        OFFER_COLUMNS, trade.get("id").toString()));
    trade.put("estimates", rows("select " + ESTIMATE_COLUMNS + " from trade_estimates where trade_id = :id order by created_at, id",
        ESTIMATE_COLUMNS, trade.get("id").toString()));
    trade.put("deskCheckCases", rows("select case_id, created_at from trade_case_journeys where journey_id = :id order by created_at, case_id",
        "case_id, created_at", journeyId));
    return trade;
  }

  /**
   * Deletes the journey's Trade Vehicle, its written offers, estimates and its Desk Check case links, then
   * verifies they are gone. The offers' evidence is erased by the ordinary document retention sweep.
   */
  public void deleteForJourney(String journeyId) {
    var now = Instant.now();
    manager.createNativeQuery("""
        update source_documents set retention_deadline = :now, updated_at = :now
        where id in (select o.evidence_document_id from trade_offers o join trade_vehicles t on t.id = o.trade_id where t.journey_id = :id)
          and purpose = 'TRADE_OFFER_EVIDENCE' and retention_deadline > :now
        """).setParameter("now", now).setParameter("id", journeyId).executeUpdate();
    for (String[] step : List.of(
        new String[] {"trade_offers", "trade_id in (select id from trade_vehicles where journey_id = :id)"},
        new String[] {"trade_estimates", "trade_id in (select id from trade_vehicles where journey_id = :id)"},
        new String[] {"trade_case_journeys", "journey_id = :id"},
        new String[] {"trade_vehicles", "journey_id = :id"})) {
      manager.createNativeQuery("delete from " + step[0] + " where " + step[1]).setParameter("id", journeyId).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + step[0] + " where " + step[1])
          .setParameter("id", journeyId).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Journey trade deletion verification failed");
    }
  }

  private List<Map<String, Object>> rows(String query, String columns, String id) {
    String[] names = columns.split(", ");
    List<?> result = manager.createNativeQuery(query).setParameter("id", id).getResultList();
    return result.stream().map(item -> {
      Object[] values = item instanceof Object[] array ? array : new Object[] {item};
      var row = new LinkedHashMap<String, Object>();
      for (int i = 0; i < names.length; i++) row.put(camel(names[i]), values[i] == null ? null
          : values[i] instanceof Number || values[i] instanceof Boolean ? values[i] : values[i].toString());
      return (Map<String, Object>) row;
    }).toList();
  }

  private static String camel(String column) {
    var result = new StringBuilder();
    boolean upper = false;
    for (char c : column.toCharArray()) {
      if (c == '_') { upper = true; continue; }
      result.append(upper ? Character.toUpperCase(c) : c);
      upper = false;
    }
    return result.toString();
  }
}
