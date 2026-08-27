package com.dealanalyzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scores normalized extraction output against a frozen, field-labeled source of truth. */
public final class ExtractionEvaluator {
  public Score score(JsonNode expectedFields, JsonNode observedFields) {
    return score(expectedFields, observedFields, null, 0.0);
  }

  public Score score(
      JsonNode expectedFields,
      JsonNode observedFields,
      JsonNode fieldPolicies,
      double minimumMaterialPresenceRecall) {
    if (!expectedFields.isObject() || !observedFields.isObject()) {
      throw new IllegalArgumentException("Extraction fields must be JSON objects");
    }
    if (fieldPolicies != null && !fieldPolicies.isObject()) {
      throw new IllegalArgumentException("Extraction field policies must be a JSON object");
    }
    if (minimumMaterialPresenceRecall < 0.0 || minimumMaterialPresenceRecall > 1.0) {
      throw new IllegalArgumentException("Material presence threshold must be between 0 and 1");
    }

    int expectedCount = 0;
    int presentCount = 0;
    int expectedNumericCount = 0;
    int exactNumericCount = 0;
    int materialCount = 0;
    int presentMaterialCount = 0;
    double expectedWeight = 0.0;
    double presentWeight = 0.0;
    double exactWeight = 0.0;
    double expectedNumericWeight = 0.0;
    double exactNumericWeight = 0.0;
    Map<String, FieldScore> fieldScores = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = expectedFields.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> expected = fields.next();
      JsonNode fieldPolicy = fieldPolicies == null ? null : fieldPolicies.get(expected.getKey());
      double weight = fieldPolicy == null ? 1.0 : fieldPolicy.path("weight").asDouble(1.0);
      boolean material = fieldPolicy != null && fieldPolicy.path("material").asBoolean();
      if (!Double.isFinite(weight) || weight <= 0.0) {
        throw new IllegalArgumentException(
            "Extraction field weight must be positive for: " + expected.getKey());
      }

      expectedCount++;
      expectedWeight += weight;
      JsonNode observed = observedFields.get(expected.getKey());
      boolean present = observed != null && !observed.isNull() && !observed.isMissingNode();
      if (present) {
        presentCount++;
        presentWeight += weight;
      }
      if (material) {
        materialCount++;
        if (present) {
          presentMaterialCount++;
        }
      }
      boolean exact = present && valuesMatch(expected.getValue(), observed);
      if (exact) {
        exactWeight += weight;
      }
      boolean numericExact = false;
      if (expected.getValue().isNumber()) {
        expectedNumericCount++;
        expectedNumericWeight += weight;
        if (present && observed.isNumber()
            && expected.getValue().decimalValue().compareTo(observed.decimalValue()) == 0) {
          exactNumericCount++;
          exactNumericWeight += weight;
          numericExact = true;
        }
      }
      fieldScores.put(
          expected.getKey(), new FieldScore(present, exact, numericExact, weight, material));
    }

    double materialPresenceRecall = ratio(presentMaterialCount, materialCount);
    return new Score(
        expectedCount,
        presentCount,
        expectedNumericCount,
        exactNumericCount,
        ratio(presentCount, expectedCount),
        ratio(exactNumericCount, expectedNumericCount),
        Map.copyOf(fieldScores),
        ratio(presentWeight, expectedWeight),
        ratio(exactWeight, expectedWeight),
        ratio(exactNumericWeight, expectedNumericWeight),
        materialPresenceRecall,
        materialPresenceRecall >= minimumMaterialPresenceRecall);
  }

  private static double ratio(int numerator, int denominator) {
    return denominator == 0 ? 1.0 : (double) numerator / denominator;
  }

  private static double ratio(double numerator, double denominator) {
    return denominator == 0.0 ? 1.0 : numerator / denominator;
  }

  private static boolean valuesMatch(JsonNode expected, JsonNode observed) {
    if (expected.isNumber() && observed.isNumber()) {
      return expected.decimalValue().compareTo(observed.decimalValue()) == 0;
    }
    return expected.equals(observed);
  }

  public record FieldScore(
      boolean present,
      boolean exactMatch,
      boolean numericExactMatch,
      double weight,
      boolean material) {}

  public record Score(
      int expectedFieldCount,
      int presentFieldCount,
      int expectedNumericFieldCount,
      int exactNumericFieldCount,
      double presenceRecall,
      double numericExactMatchRate,
      Map<String, FieldScore> fields,
      double weightedPresenceRecall,
      double weightedExactMatchRate,
      double weightedNumericExactMatchRate,
      double materialPresenceRecall,
      boolean passesMaterialPresenceGate) {}
}
