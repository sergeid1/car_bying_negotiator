package com.dealanalyzer.market;

import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.MarketAssessmentView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Buyer wording frozen at report creation; rendering never refreshes evidence or calls providers. */
public final class MarketReportEvidence {
  private MarketReportEvidence() {}

  public static boolean current(MarketAssessmentView assessment, Instant now) {
    return assessment != null && assessment.provider() != null
        && assessment.provider().retrievedAt() != null
        && !assessment.provider().retrievedAt().isBefore(now.minus(MarketProviderContract.MAX_SNAPSHOT_AGE))
        && !assessment.provider().retrievedAt().isAfter(now.plusSeconds(60));
  }

  public static Summary describe(MarketAssessmentView assessment, Instant now, boolean usable) {
    var paragraphs = new ArrayList<String>();
    if (assessment == null) return new Summary("UNAVAILABLE", List.of(
        "Market evidence is unavailable: no market assessment was recorded. No market rating is drawn."));
    boolean current = current(assessment, now);
    String status = !current ? "UNAVAILABLE" : assessment.status();
    if (assessment.provider() != null && assessment.provider().retrievedAt() != null
        && !"MARKET_PROVIDER".equals(assessment.provider().source()))
      paragraphs.add("Market data retrieved: " + assessment.provider().retrievedAt() + ".");
    else paragraphs.add("Market data retrieval time is unavailable.");
    if (assessment.vintage() != null) paragraphs.add("Observation window: "
        + assessment.vintage().oldestObservationAt() + " to " + assessment.vintage().newestObservationAt() + ".");
    else paragraphs.add("Observation window is unavailable.");
    if (!current) paragraphs.add("Market evidence is unavailable: the stored snapshot is stale or has an invalid retrieval time. No market rating is drawn.");
    else if ("INSUFFICIENT_DATA".equals(status)) paragraphs.add("Too few compatible, current comparables ("
        + assessment.sampleSize() + ") to support a market conclusion. No market rating is drawn.");
    else if (!"AVAILABLE".equals(status)) paragraphs.add("Market evidence is unavailable. "
        + (assessment.limitation() == null ? "The provider supplied no usable evidence." : assessment.limitation())
        + " No market rating is drawn.");
    else if (!usable) {
      status = "UNAVAILABLE";
      paragraphs.add("Market evidence does not match the current confirmed vehicle. No market rating is drawn.");
    } else {
      paragraphs.add("Uncertainty: " + assessment.confidence().toLowerCase(java.util.Locale.ROOT)
          + " confidence from " + assessment.sampleSize() + " usable comparables. This is an estimated selling-price range.");
    }
    if (assessment.limitation() != null && "AVAILABLE".equals(status))
      paragraphs.add(assessment.limitation());
    if (assessment.provider() != null && "DETERMINISTIC_LICENSED_FIXTURE".equals(assessment.provider().source()))
      paragraphs.add("Development fixture only. No licensed market source is integrated.");
    return new Summary(status, List.copyOf(paragraphs));
  }

  public record Summary(String status, List<String> paragraphs) {}
}
