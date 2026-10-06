package com.dealanalyzer.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * The Buyer's side of the own-email-app send path (tickets 82 and 84) for acceptance tests: read the handed-off
 * text and press "I sent it". The platform sends nothing, so tests count the outbound messages instead.
 */
final class DealerHandoffTestSupport {
  private DealerHandoffTestSupport() {}

  /** "I sent it" for a handed-off message; returns the message as now recorded. */
  static JsonNode markSent(RestClient client, String casePath, String token, String messageId) throws Exception {
    return HttpAcceptanceHarness.post(client, casePath + "/handoffs/" + messageId + "/sent", Map.of(),
        "sent-" + UUID.randomUUID(), token);
  }

  /** Hands off then marks sent the message an approval response points at. */
  static JsonNode markSent(RestClient client, String casePath, String token, JsonNode approved) throws Exception {
    return markSent(client, casePath, token, approved.path("messageId").asText());
  }

  /** The exact text handed to the Buyer's mail app. */
  static JsonNode handoff(RestClient client, String casePath, String token, String messageId) throws Exception {
    return HttpAcceptanceHarness.get(client, casePath + "/handoffs/" + messageId, token);
  }

  /** Outbound dealer messages the Buyer marked sent for the case. */
  static long sent(JdbcTemplate jdbc, String caseId) {
    return jdbc.queryForObject("select count(*) from dealer_relay_messages where case_id = ? and direction = 'OUTBOUND' and state = 'SENT'",
        Long.class, caseId);
  }

  /** Outbound dealer messages of the case, whatever their state. None of them was sent by the platform. */
  static long outbound(JdbcTemplate jdbc, String caseId) {
    return jdbc.queryForObject("select count(*) from dealer_relay_messages where case_id = ? and direction = 'OUTBOUND'",
        Long.class, caseId);
  }
}
