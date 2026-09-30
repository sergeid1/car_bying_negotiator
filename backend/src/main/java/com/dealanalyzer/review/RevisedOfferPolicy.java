package com.dealanalyzer.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.*;

/** Semantic comparison preserves all differences; materiality only changes severity. */
public final class RevisedOfferPolicy {
  public static final String VERSION = "revised-offer.v1";
  public static final String TOLERANCE = "revised-materiality.v1: USD 1.00; APR 0.1 percentage points; other changes material; approved limits zero tolerance";
  public record Discrepancy(String comparison, String term, String kind, JsonNode expected,
      JsonNode observed, BigDecimal delta, String unit, String severity, String reason,
      JsonNode expectedEvidence, JsonNode observedEvidence, Double confidence,
      boolean uncertain, String buyerAction) {}

  public List<Discrepancy> compare(String comparison, JsonNode expected, JsonNode observed) {
    var before = terms(expected); var after = terms(observed);
    Set<String> keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
    List<Discrepancy> result = new ArrayList<>();
    for (String key : keys) {
      JsonNode a = before.get(key), b = after.get(key);
      boolean uncertain = b != null && (b.path("category").asText().equals("UNKNOWN")
          || b.path("taxClaim").asText().equals("UNKNOWN")
          || b.path("optionalityClaim").asText().equals("UNKNOWN")
          || b.path("value").isNull() || b.has("buyerVerified") && !b.path("buyerVerified").asBoolean());
      JsonNode av = value(a), bv = value(b);
      if (Objects.equals(av, bv) && !uncertain) continue;
      String unit = b == null ? unit(a) : unit(b);
      BigDecimal delta = unit.equals(unit(a)) ? delta(av, bv) : null;
      String kind = a == null ? "ADDED" : b == null ? "MISSING" : Objects.equals(av, bv) ? "UNCERTAIN" : "CHANGED";
      BigDecimal threshold = unit.equals("USD_MINOR") ? new BigDecimal("100")
          : unit.equals("PERCENT") ? new BigDecimal("0.1") : BigDecimal.ZERO;
      boolean metadataChanged = a != null && b != null && List.of("unit", "currency", "category", "taxClaim", "optionalityClaim")
          .stream().anyMatch(field -> !a.path(field).equals(b.path(field)));
      boolean small = delta != null && delta.abs().compareTo(threshold) < 0 && !uncertain && !metadataChanged;
      result.add(new Discrepancy(comparison, key, kind, av, bv, delta, unit,
          small ? "INFORMATIONAL" : "MATERIAL",
          uncertain ? "Unresolved classification or unverified value requires clarification."
              : "The term is " + kind.toLowerCase(Locale.ROOT) + " relative to " + comparison + ".",
          a, b, b != null && b.has("confidence") ? b.get("confidence").asDouble() : null,
          uncertain, uncertain ? "Ask the dealer to clarify and verify this term."
              : "Review this difference with the dealer before accepting."));
    }
    return List.copyOf(result);
  }

  public JsonNode compareCash(JsonNode expected, JsonNode observed) {
    var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    if (expected == null || observed == null || expected.isMissingNode()
        || !expected.at("/outTheDoorTotal/minorUnits").isIntegralNumber()
        || !observed.at("/outTheDoorTotal/minorUnits").isIntegralNumber()
        || !expected.at("/taxes/minorUnits").isIntegralNumber()
        || !observed.at("/taxes/minorUnits").isIntegralNumber()
        || !expected.at("/outTheDoorTotal/currency").equals(observed.at("/outTheDoorTotal/currency"))) {
      result.put("availability", "UNAVAILABLE");
      result.put("reason", "Both offer calculations in the same currency are required; missing values are not zero.");
      return result;
    }
    long total = Math.subtractExact(observed.at("/outTheDoorTotal/minorUnits").longValue(), expected.at("/outTheDoorTotal/minorUnits").longValue());
    long tax = Math.subtractExact(observed.at("/taxes/minorUnits").longValue(), expected.at("/taxes/minorUnits").longValue());
    result.put("availability", "ESTIMATED"); result.put("currency", observed.at("/outTheDoorTotal/currency").asText());
    result.put("preTaxDeltaMinor", Math.subtractExact(total, tax)); result.put("taxEffectMinor", tax);
    result.put("outTheDoorDeltaMinor", total);
    result.put("expectedOutTheDoorMinor", expected.at("/outTheDoorTotal/minorUnits").longValue());
    result.put("observedOutTheDoorMinor", observed.at("/outTheDoorTotal/minorUnits").longValue());
    result.put("expectedTaxesMinor", expected.at("/taxes/minorUnits").longValue());
    result.put("observedTaxesMinor", observed.at("/taxes/minorUnits").longValue());
    result.put("reason", "Complete pre-tax OTD difference, including fees, add-ons and reductions; tax effect is the difference between separately calculated taxes.");
    result.put("expectedPolicy", expected.path("policyVersion").asText()); result.put("observedPolicy", observed.path("policyVersion").asText());
    return result;
  }

  private Map<String, JsonNode> terms(JsonNode terms) {
    Map<String, JsonNode> result = new LinkedHashMap<>();
    if (terms == null || terms.isNull()) return result;
    for (JsonNode candidate : terms.path("candidates"))
      add(result, "candidate:" + candidate.path("field").asText(), candidate);
    for (JsonNode line : terms.path("lineItems"))
      add(result, "line:" + line.path("originalLabel").asText().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), line);
    return result;
  }

  // Keep duplicate labels as distinct occurrences, never overwrite or aggregate charges.
  private void add(Map<String, JsonNode> result, String key, JsonNode value) {
    int occurrence = 1;
    while (result.containsKey(key + "#" + occurrence)) occurrence++;
    result.put(key + "#" + occurrence, value);
  }

  private JsonNode value(JsonNode term) {
    if (term == null) return null;
    var value = JsonNodeFactory.instance.objectNode();
    for (String key : List.of("value", "unit", "amountMinor", "currency", "category", "taxClaim", "optionalityClaim"))
      if (term.has(key)) value.set(key, term.get(key));
    return value;
  }

  private String unit(JsonNode term) {
    return term == null ? "" : term.has("amountMinor")
        ? term.path("currency").asText() + "_MINOR" : term.path("unit").asText();
  }

  private BigDecimal delta(JsonNode a, JsonNode b) {
    if (a == null || b == null) return null;
    try {
      return new BigDecimal(b.has("amountMinor") ? b.get("amountMinor").asText() : b.path("value").asText())
          .subtract(new BigDecimal(a.has("amountMinor") ? a.get("amountMinor").asText() : a.path("value").asText()));
    } catch (NumberFormatException invalid) { return null; }
  }
}
