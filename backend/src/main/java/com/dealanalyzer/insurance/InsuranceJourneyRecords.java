package com.dealanalyzer.insurance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A journey's insurance application for the account export and journey deletion, inside the caller's transaction. */
@Component
public class InsuranceJourneyRecords {
  private static final String APPLICATION_COLUMNS = "id, application_json, schema_version, revision, changed_at, created_at";
  private static final String REQUEST_COLUMNS = "id, application_revision, consent_text_version, consent_agreed_at, provider, "
      + "provider_version, licence_metadata, synthetic, availability, reason, retrieved_at, created_at";
  private static final String QUOTE_COLUMNS = "id, request_id, source, insurer, outcome, premium_minor, term, coverage_json, "
      + "quoted_at, expires_at, continue_url, created_at";
  private static final String APPLICATION = "application_id in (select id from insurance_applications where journey_id = :id)";

  private final EntityManager manager;
  private final ObjectMapper json;

  InsuranceJourneyRecords(EntityManager manager, ObjectMapper json) { this.manager = manager; this.json = json; }

  /** The journey's application with every consent-backed quote request and every quote, or null. */
  public Map<String, Object> exportForJourney(String journeyId) {
    var applications = rows("select " + APPLICATION_COLUMNS + " from insurance_applications where journey_id = :id",
        APPLICATION_COLUMNS, journeyId);
    if (applications.isEmpty()) return null;
    var application = applications.getFirst();
    application.put("application", parse(application.remove("applicationJson")));
    application.put("quoteRequests", rows("select " + REQUEST_COLUMNS + " from insurance_quote_requests where " + APPLICATION
        + " order by created_at, id", REQUEST_COLUMNS, journeyId));
    var quotes = rows("select " + QUOTE_COLUMNS + " from insurance_quotes where " + APPLICATION + " order by created_at, id",
        QUOTE_COLUMNS, journeyId);
    for (var quote : quotes) quote.put("coverage", parse(quote.remove("coverageJson")));
    application.put("quotes", quotes);
    return application;
  }

  /** Deletes the journey's application, its quote requests and quotes, then verifies they are gone. */
  public void deleteForJourney(String journeyId) {
    for (String[] step : List.of(
        new String[] {"insurance_quotes", APPLICATION},
        new String[] {"insurance_quote_requests", APPLICATION},
        new String[] {"insurance_applications", "journey_id = :id"})) {
      manager.createNativeQuery("delete from " + step[0] + " where " + step[1]).setParameter("id", journeyId).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + step[0] + " where " + step[1])
          .setParameter("id", journeyId).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Journey insurance deletion verification failed");
    }
  }

  private Object parse(Object value) {
    if (value == null) return null;
    try { return json.readTree(value.toString()); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Insurance export unavailable"); }
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
