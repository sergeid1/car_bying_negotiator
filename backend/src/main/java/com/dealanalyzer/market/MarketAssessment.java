package com.dealanalyzer.market;

import com.dealanalyzer.market.MarketDataProvider.ProviderSnapshot;
import java.time.Instant;
import java.util.List;

public record MarketAssessment(
    Status status,
    PriceRange range,
    String geography,
    Vintage vintage,
    int sampleSize,
    String methodVersion,
    Confidence confidence,
    String limitation,
    ProviderSnapshot provider,
    List<ComparableDecision> decisions) {
  public MarketAssessment {
    decisions = List.copyOf(decisions);
  }

  public enum Status { AVAILABLE, INSUFFICIENT_DATA, UNAVAILABLE }
  public enum Confidence { LOW, MEDIUM, HIGH, UNAVAILABLE }
  public enum Decision { INCLUDED, EXCLUDED }

  public record PriceRange(long lowMinor, long highMinor, String currency) {}
  public record Vintage(Instant oldestObservationAt, Instant newestObservationAt) {}
  public record Adjustment(
      long amountMinor, String precision, String explanation) {}
  public record ComparableDecision(
      MarketDataProvider.ComparableObservation observation,
      Decision decision,
      String reasonCode,
      String reason,
      List<String> matchFactors,
      Adjustment adjustment,
      Long adjustedPriceMinor) {
    public ComparableDecision {
      matchFactors = matchFactors == null ? List.of() : List.copyOf(matchFactors);
    }
  }
}
