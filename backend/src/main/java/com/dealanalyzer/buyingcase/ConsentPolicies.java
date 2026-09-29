package com.dealanalyzer.buyingcase;

import java.util.Map;

/**
 * The consent policy versions currently presented at intake. A consent record has to name the
 * policy the Buyer actually saw, so the form reads these rather than inventing identifiers.
 */
public final class ConsentPolicies {
  public static final String SERVICE_PROCESSING = "service-processing.v1";
  public static final String HUMAN_REVIEW = "human-review.v1";
  public static final String ANONYMIZED_RESEARCH = "anonymized-research.v1";

  public static Map<String, String> current() {
    return Map.of(
        "serviceProcessing", SERVICE_PROCESSING,
        "humanReview", HUMAN_REVIEW,
        "anonymizedResearch", ANONYMIZED_RESEARCH);
  }

  private ConsentPolicies() {}
}
