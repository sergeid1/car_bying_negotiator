package com.dealanalyzer.journey;

import java.time.Instant;
import java.util.List;

/**
 * One immutable Shopping Brief version with reference-list labels. Later shopping steps (Reference
 * Vehicles, inventory search) read the current version through {@link JourneyService#currentBrief}.
 */
public record ShoppingBrief(String journeyId, int version, String referenceListVersion, int yearFrom, int yearTo,
    Choice make, Choice model, List<Choice> trims, int maxMileage, long priceCeilingMinor, String currency,
    String postalCode, String jurisdiction, int radiusMiles, List<Choice> mustHaveFeatures,
    List<Choice> niceToHaveFeatures, Instant createdAt) {
  public record Choice(String id, String label) {}

  public ShoppingBrief {
    trims = List.copyOf(trims); mustHaveFeatures = List.copyOf(mustHaveFeatures);
    niceToHaveFeatures = List.copyOf(niceToHaveFeatures);
  }
}
