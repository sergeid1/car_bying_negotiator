package com.dealanalyzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/** Evaluates captured provider responses against the normalized market-provider contract. */
public final class ProviderContractEvaluator {
  private static final String SUPPORTED_SCHEMA = "market-provider.v1";

  private final Instant now;
  private final Duration maxAge;

  public ProviderContractEvaluator(Instant now, Duration maxAge) {
    if (maxAge.isNegative()) {
      throw new IllegalArgumentException("Provider maximum age cannot be negative");
    }
    this.now = now;
    this.maxAge = maxAge;
  }

  public Result evaluate(JsonNode fixture) {
    if ("TIMEOUT".equals(fixture.path("transportOutcome").asText())) {
      return new Result(Status.TIMEOUT, 0);
    }
    if ("RATE_LIMITED".equals(fixture.path("transportOutcome").asText())) {
      return new Result(Status.RATE_LIMITED, 0);
    }

    JsonNode response = fixture.path("response");
    int observationCount = uniqueObservationCount(response.path("observations"));
    if (!SUPPORTED_SCHEMA.equals(response.path("schemaVersion").asText())) {
      return new Result(Status.SCHEMA_DRIFT, observationCount);
    }
    if (response.path("vinAvailable").isBoolean()
        && !response.get("vinAvailable").asBoolean()) {
      return new Result(Status.MISSING_VIN, observationCount);
    }
    if (!hasCompleteRange(response)) {
      return new Result(Status.PARTIAL_DATA, observationCount);
    }

    Instant observedAt;
    try {
      observedAt = Instant.parse(response.get("observedAt").asText());
    } catch (DateTimeParseException exception) {
      return new Result(Status.PARTIAL_DATA, observationCount);
    }
    if (observedAt.isBefore(now.minus(maxAge))) {
      return new Result(Status.STALE_DATA, observationCount);
    }
    if (observationCount < response.get("observations").size()) {
      return new Result(Status.DUPLICATES_FILTERED, observationCount);
    }
    return new Result(Status.SUCCESS, observationCount);
  }

  private static boolean hasCompleteRange(JsonNode response) {
    return response.hasNonNull("observedAt")
        && response.path("lowMinor").isIntegralNumber()
        && response.path("highMinor").isIntegralNumber()
        && response.path("observations").isArray();
  }

  private static int uniqueObservationCount(JsonNode observations) {
    if (!observations.isArray()) {
      return 0;
    }
    Set<String> listingIds = new HashSet<>();
    observations.forEach(observation -> {
      if (observation.hasNonNull("listingId")) {
        listingIds.add(observation.get("listingId").asText());
      }
    });
    return listingIds.size();
  }

  public record Result(Status status, int uniqueObservationCount) {}

  public enum Status {
    SUCCESS,
    TIMEOUT,
    RATE_LIMITED,
    MISSING_VIN,
    STALE_DATA,
    PARTIAL_DATA,
    DUPLICATES_FILTERED,
    SCHEMA_DRIFT
  }
}
