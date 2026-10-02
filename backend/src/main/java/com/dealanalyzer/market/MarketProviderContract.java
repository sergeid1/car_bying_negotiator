package com.dealanalyzer.market;

import java.time.Duration;
import java.time.Instant;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;

/** Validation at the adapter boundary, before values can become assessment evidence. */
public final class MarketProviderContract {
  public static final Duration MAX_SNAPSHOT_AGE = Duration.ofDays(45);
  private MarketProviderContract() {}

  public static String failure(ProviderResult result, Instant now) {
    if (result == null || result.snapshot() == null)
      return "Provider response did not include snapshot metadata; market analysis is unavailable.";
    var snapshot = result.snapshot();
    if (!"market-provider.v1".equals(snapshot.schemaVersion()))
      return "Provider schema drift; market analysis is unavailable.";
    if (blank(snapshot.source()) || blank(snapshot.providerVersion())
        || blank(snapshot.licenseMetadata()) || snapshot.retrievedAt() == null)
      return "Provider response has missing snapshot metadata; market analysis is unavailable.";
    if (snapshot.retrievedAt().isBefore(now.minus(MAX_SNAPSHOT_AGE))
        || snapshot.retrievedAt().isAfter(now.plusSeconds(60)))
      return "Provider snapshot is stale or has an invalid retrieval time; market analysis is unavailable.";
    if (result.availability() == null)
      return "Provider availability is missing; market analysis is unavailable.";
    if (result.availability() == MarketDataProvider.Availability.UNAVAILABLE) {
      if (blank(result.unavailableReason()) || !result.observations().isEmpty())
        return "Provider unavailability contract failed; market analysis is unavailable.";
      return null;
    }
    if (blank(snapshot.artifactReference()))
      return "Provider response has missing snapshot metadata; market analysis is unavailable.";
    for (var observation : result.observations()) {
      if (blank(observation.providerObservationId()) || blank(observation.currency())
          || !observation.currency().matches("[A-Z]{3}") || observation.priceMinor() == null
          || observation.priceMinor() < 0 || observation.observedAt() == null
          || observation.observedAt().isAfter(snapshot.retrievedAt().plusSeconds(60)))
        return "Provider observation contract failed; market analysis is unavailable.";
    }
    return null;
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
