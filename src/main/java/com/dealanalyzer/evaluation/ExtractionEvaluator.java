package com.dealanalyzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/** Scores normalized extraction output against a frozen, field-labeled source of truth. */
public final class ExtractionEvaluator {
  public Score score(JsonNode expectedFields, JsonNode observedFields) {
    if (!expectedFields.isObject() || !observedFields.isObject()) {
      throw new IllegalArgumentException("Extraction fields must be JSON objects");
    }

    int expectedCount = 0;
    int presentCount = 0;
    int expectedNumericCount = 0;
    int exactNumericCount = 0;
    Iterator<Map.Entry<String, JsonNode>> fields = expectedFields.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> expected = fields.next();
      expectedCount++;
      JsonNode observed = observedFields.get(expected.getKey());
      boolean present = observed != null && !observed.isNull() && !observed.isMissingNode();
      if (present) {
        presentCount++;
      }
      if (expected.getValue().isNumber()) {
        expectedNumericCount++;
        if (present && observed.isNumber()
            && expected.getValue().decimalValue().compareTo(observed.decimalValue()) == 0) {
          exactNumericCount++;
        }
      }
    }

    return new Score(
        expectedCount,
        presentCount,
        expectedNumericCount,
        exactNumericCount,
        ratio(presentCount, expectedCount),
        ratio(exactNumericCount, expectedNumericCount));
  }

  private static double ratio(int numerator, int denominator) {
    return denominator == 0 ? 1.0 : (double) numerator / denominator;
  }

  public record Score(
      int expectedFieldCount,
      int presentFieldCount,
      int expectedNumericFieldCount,
      int exactNumericFieldCount,
      double presenceRecall,
      double numericExactMatchRate) {}
}
