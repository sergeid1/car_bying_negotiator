package com.dealanalyzer.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Copy only code-owned policy declarations at report creation, never by fetching payloads for support. */
public final class ReportPolicyMetadata {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> KEYS = Set.of("review", "revisedReview", "materiality", "rating",
      "cashEconomics", "market", "financing", "priceBoundaries", "marketAssessment", "report");
  private ReportPolicyMetadata() {}
  public static String snapshot(String payload) {
    try {
      var source = JSON.readTree(payload).path("analysis").path("policyVersions");
      var result = JSON.createObjectNode();
      source.fields().forEachRemaining(entry -> {
        if (KEYS.contains(entry.getKey()) && entry.getValue().isTextual()) {
          String version = entry.getValue().asText().split(":", 2)[0];
          if (version.equals("UNAVAILABLE") || version.matches("[a-z][a-z0-9-]{0,80}\\.v[0-9]{1,4}"))
            result.put(entry.getKey(), version);
        }
      });
      return JSON.writeValueAsString(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid structured report");
    }
  }
  static Object read(String metadata) {
    try { return JSON.readTree(metadata); }
    catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return java.util.Map.of(); }
  }
}
