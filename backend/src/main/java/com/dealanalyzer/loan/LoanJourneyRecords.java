package com.dealanalyzer.loan;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A journey's loan application for the account export and journey deletion, inside the caller's transaction. */
@Component
public class LoanJourneyRecords {
  private static final String APPLICATION_COLUMNS = "id, application_json, schema_version, revision, chosen_offer_id, chosen_at, "
      + "changed_at, created_at";
  private static final String REQUEST_COLUMNS = "id, application_revision, authorization_text_version, authorized_at, provider, "
      + "provider_version, licence_metadata, synthetic, availability, reason, retrieved_at, created_at";
  private static final String OFFER_COLUMNS = "id, request_id, source, lender, outcome, amount_minor, apr_percent, term_months, "
      + "upfront_fees_minor, lender_payment_minor, maximum_vehicle_age_years, maximum_mileage, minimum_down_payment_minor, "
      + "inquiry_type, offered_at, expires_at, continue_url, created_at";
  private static final String APPLICATION = "application_id in (select id from loan_applications where journey_id = :id)";

  private final EntityManager manager;
  private final ObjectMapper json;

  LoanJourneyRecords(EntityManager manager, ObjectMapper json) { this.manager = manager; this.json = json; }

  /** The journey's application with every authorization-backed request and every offer, or null. */
  public Map<String, Object> exportForJourney(String journeyId) {
    var applications = rows("select " + APPLICATION_COLUMNS + " from loan_applications where journey_id = :id",
        APPLICATION_COLUMNS, journeyId);
    if (applications.isEmpty()) return null;
    var application = applications.getFirst();
    application.put("application", parse(application.remove("applicationJson")));
    application.put("preApprovalRequests", rows("select " + REQUEST_COLUMNS + " from loan_pre_approval_requests where " + APPLICATION
        + " order by created_at, id", REQUEST_COLUMNS, journeyId));
    application.put("offers", rows("select " + OFFER_COLUMNS + " from loan_offers where " + APPLICATION + " order by created_at, id",
        OFFER_COLUMNS, journeyId));
    return application;
  }

  /** Deletes the journey's application, its requests and offers, then verifies they are gone. */
  public void deleteForJourney(String journeyId) {
    for (String[] step : List.of(
        new String[] {"loan_offers", APPLICATION},
        new String[] {"loan_pre_approval_requests", APPLICATION},
        new String[] {"loan_applications", "journey_id = :id"})) {
      manager.createNativeQuery("delete from " + step[0] + " where " + step[1]).setParameter("id", journeyId).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + step[0] + " where " + step[1])
          .setParameter("id", journeyId).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Journey loan deletion verification failed");
    }
  }

  private Object parse(Object value) {
    if (value == null) return null;
    try { return json.readTree(value.toString()); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Loan export unavailable"); }
  }

  private List<Map<String, Object>> rows(String query, String columns, String id) {
    String[] names = columns.split(", ");
    List<?> result = manager.createNativeQuery(query).setParameter("id", id).getResultList();
    return result.stream().map(item -> {
      Object[] values = item instanceof Object[] array ? array : new Object[] {item};
      var row = new LinkedHashMap<String, Object>();
      for (int i = 0; i < names.length; i++) row.put(camel(names[i]), values[i] == null ? null
          : values[i] instanceof java.math.BigDecimal decimal ? LoanShoppingService.plain(decimal)
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
