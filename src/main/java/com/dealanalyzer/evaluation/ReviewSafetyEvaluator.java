package com.dealanalyzer.evaluation;

import com.dealanalyzer.report.DealAnalysis.ReviewStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Enforces the fail-closed invariant for positive Deal Review outcomes. */
public final class ReviewSafetyEvaluator {
  public void assertNoUnsafeMatches(JsonNode fixtures) {
    if (!fixtures.isArray()) {
      throw new IllegalArgumentException("Review safety fixtures must be a JSON array");
    }

    List<String> unsafeFixtureNames = new ArrayList<>();
    fixtures.forEach(fixture -> {
      ReviewStatus status = ReviewStatus.valueOf(fixture.path("actualStatus").asText());
      boolean safePositive = fixture.path("allMaterialFieldsVerified").asBoolean()
          && !fixture.path("materialDiscrepancyPresent").asBoolean();
      if (status == ReviewStatus.MATCHES_APPROVED_BASELINE && !safePositive) {
        unsafeFixtureNames.add(fixture.path("name").asText("unnamed review fixture"));
      }
    });

    if (!unsafeFixtureNames.isEmpty()) {
      throw new IllegalStateException(
          "Unsafe MATCHES_APPROVED_BASELINE result for: " + String.join(", ", unsafeFixtureNames));
    }
  }
}
