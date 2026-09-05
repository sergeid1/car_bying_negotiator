package com.dealanalyzer.market;

import com.dealanalyzer.market.MarketAssessment.Adjustment;
import com.dealanalyzer.market.MarketAssessment.ComparableDecision;
import com.dealanalyzer.market.MarketAssessment.Confidence;
import com.dealanalyzer.market.MarketAssessment.Decision;
import com.dealanalyzer.market.MarketAssessment.PriceRange;
import com.dealanalyzer.market.MarketAssessment.Status;
import com.dealanalyzer.market.MarketAssessment.Vintage;
import com.dealanalyzer.market.MarketDataProvider.ComparableObservation;
import com.dealanalyzer.market.MarketDataProvider.MarketVehicle;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Versioned, deterministic selection and calibration rules for normalized market observations. */
public final class MarketAssessmentPolicy {
  public static final String VERSION = "market-selection.v1";
  private static final Duration MAX_AGE = Duration.ofDays(45);
  private static final long MIN_PRICE_MINOR = 500_000L;
  private static final long MAX_PRICE_MINOR = 20_000_000L;

  public MarketAssessment assess(MarketVehicle target, ProviderResult providerResult) {
    if (providerResult.availability() == MarketDataProvider.Availability.UNAVAILABLE) {
      return new MarketAssessment(
          Status.UNAVAILABLE, null, target.geography(), null, 0, VERSION,
          Confidence.UNAVAILABLE, providerResult.unavailableReason(), providerResult.snapshot(),
          List.of());
    }

    List<ComparableDecision> decisions = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ComparableObservation observation : providerResult.observations()) {
      decisions.add(decide(target, observation, providerResult, seen));
    }
    List<ComparableDecision> included = decisions.stream()
        .filter(value -> value.decision() == Decision.INCLUDED).toList();
    if (included.size() < 3) {
      return new MarketAssessment(
          Status.INSUFFICIENT_DATA, null, target.geography(), vintage(included), included.size(),
          VERSION, Confidence.LOW,
          "At least three compatible, current observations are required.",
          providerResult.snapshot(), decisions);
    }

    long rawLow = included.stream().mapToLong(ComparableDecision::adjustedPriceMinor).min()
        .orElseThrow();
    long rawHigh = included.stream().mapToLong(ComparableDecision::adjustedPriceMinor).max()
        .orElseThrow();
    double spreadRatio = rawHigh == 0 ? 1 : (double) (rawHigh - rawLow) / rawHigh;
    boolean divergent = spreadRatio > 0.25;
    long widening = divergent ? Math.max(100_000L, (rawHigh - rawLow) / 10)
        : included.size() < 5 ? 50_000L : 0L;
    Confidence confidence = divergent || included.size() == 3
        ? Confidence.LOW : included.size() < 6 ? Confidence.MEDIUM : Confidence.HIGH;
    String limitation = divergent
        ? "Observed prices diverge; the range is widened and confidence reduced."
        : included.size() < 5
            ? "The usable sample is sparse; the range includes a $500 uncertainty buffer."
            : null;
    String currency = included.getFirst().observation().currency();
    return new MarketAssessment(
        Status.AVAILABLE,
        new PriceRange(Math.max(0, rawLow - widening), rawHigh + widening, currency),
        target.geography(), vintage(included), included.size(), VERSION, confidence, limitation,
        providerResult.snapshot(), decisions);
  }

  private ComparableDecision decide(
      MarketVehicle target,
      ComparableObservation observation,
      ProviderResult providerResult,
      Set<String> seen) {
    if (observation.providerObservationId() == null
        || !seen.add(observation.providerObservationId())) {
      return excluded(observation, "DUPLICATE", "Duplicate provider observation identifier.");
    }
    if (observation.observedAt() == null
        || observation.observedAt().isBefore(
            providerResult.snapshot().retrievedAt().minus(MAX_AGE))) {
      return excluded(observation, "STALE", "Observation is older than the 45-day rule.");
    }
    if (!same(target.make(), observation.make())
        || !same(target.model(), observation.model())
        || !same(target.trim(), observation.trim())
        || target.year() == null || observation.year() == null
        || Math.abs(target.year() - observation.year()) > 1
        || !"USD".equals(observation.currency())) {
      return excluded(
          observation, "INCOMPATIBLE_VARIANT",
          "Make, model, trim, currency, and model year must match the declared compatibility rule.");
    }
    if (observation.priceMinor() == null
        || observation.priceMinor() < MIN_PRICE_MINOR
        || observation.priceMinor() > MAX_PRICE_MINOR) {
      return excluded(
          observation, "IMPLAUSIBLE_PRICE",
          "Price falls outside the declared $5,000-$200,000 plausibility bounds.");
    }
    long yearAdjustment = (long) (target.year() - observation.year()) * 100_000L;
    long mileageAdjustment = mileageAdjustment(target.mileage(), observation.mileage());
    long adjustment = yearAdjustment + mileageAdjustment;
    List<String> factors = List.of(
        "MAKE_MODEL_EXACT",
        "TRIM_EXACT",
        target.year().equals(observation.year()) ? "MODEL_YEAR_EXACT" : "MODEL_YEAR_WITHIN_ONE",
        mileageFactor(target.mileage(), observation.mileage()),
        "GEOGRAPHY_PROVIDER_MATCH");
    return new ComparableDecision(
        observation,
        Decision.INCLUDED,
        null,
        null,
        factors,
        new Adjustment(
            adjustment,
            "ROUNDED",
            "Model-year and mileage adjustments use declared $1,000 and $500 increments; "
                + "they are directional ranges, not appraisal precision."),
        observation.priceMinor() + adjustment);
  }

  private static ComparableDecision excluded(
      ComparableObservation observation, String code, String reason) {
    return new ComparableDecision(
        observation, Decision.EXCLUDED, code, reason, List.of(), null, null);
  }

  private static long mileageAdjustment(Long targetMileage, Long observedMileage) {
    if (targetMileage == null || observedMileage == null) return 0;
    return Math.round((observedMileage - targetMileage) / 10_000.0) * 50_000L;
  }

  private static String mileageFactor(Long targetMileage, Long observedMileage) {
    if (targetMileage == null || observedMileage == null) return "MILEAGE_UNAVAILABLE";
    return Math.abs(targetMileage - observedMileage) <= 15_000
        ? "MILEAGE_WITHIN_15000" : "MILEAGE_ADJUSTED";
  }

  private static boolean same(String left, String right) {
    return left != null && right != null
        && left.strip().toLowerCase(Locale.ROOT).equals(right.strip().toLowerCase(Locale.ROOT));
  }

  private static Vintage vintage(List<ComparableDecision> included) {
    if (included.isEmpty()) return null;
    return new Vintage(
        included.stream().map(value -> value.observation().observedAt()).min(java.util.Comparator.naturalOrder()).orElseThrow(),
        included.stream().map(value -> value.observation().observedAt()).max(java.util.Comparator.naturalOrder()).orElseThrow());
  }
}
