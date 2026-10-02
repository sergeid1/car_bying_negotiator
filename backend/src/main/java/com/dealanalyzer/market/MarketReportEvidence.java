package com.dealanalyzer.market;

import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.MarketAssessmentView;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Buyer wording frozen at report creation; rendering never refreshes evidence or calls providers. */
public final class MarketReportEvidence {
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.US);
  private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMMM d", java.util.Locale.US);
  private final Clock clock;

  public MarketReportEvidence() {
    this(Clock.systemUTC());
  }

  MarketReportEvidence(Clock clock) {
    this.clock = clock;
  }

  public boolean current(MarketAssessmentView assessment) {
    return current(assessment, clock.instant());
  }

  public Summary describe(MarketAssessmentView assessment, boolean usable) {
    return describe(assessment, clock.instant(), usable);
  }

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
      paragraphs.add("Market data retrieved: " + date(assessment.provider().retrievedAt()) + ".");
    else paragraphs.add("Market data retrieval time is unavailable.");
    if (assessment.vintage() != null) paragraphs.add("Observation window: "
        + observationWindow(assessment.vintage().oldestObservationAt(), assessment.vintage().newestObservationAt()) + ".");
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

  static String date(Instant instant) {
    return DATE.format(instant.atZone(ZoneOffset.UTC));
  }

  static String observationWindow(Instant oldest, Instant newest) {
    var start = oldest.atZone(ZoneOffset.UTC).toLocalDate();
    var end = newest.atZone(ZoneOffset.UTC).toLocalDate();
    if (start.equals(end)) return DATE.format(start);
    if (start.getYear() == end.getYear())
      return MONTH_DAY.format(start) + " to " + DATE.format(end);
    return DATE.format(start) + " to " + DATE.format(end);
  }
}
