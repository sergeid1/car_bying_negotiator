package com.dealanalyzer.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DealAnalysis(
    ReviewStatus reviewStatus,
    DealRating dealRating,
    List<String> topReasons,
    String nextAction,
    Economics economics,
    MarketAssessment marketAssessment,
    Map<String, String> policyVersions,
    List<String> limitations) {

  public record Economics(
      MoneyAmount sellingPrice,
      MoneyAmount dealerFees,
      MoneyAmount addOns,
      MoneyAmount taxes,
      MoneyAmount governmentCharges,
      MoneyAmount outTheDoorTotal,
      String calculationVersion,
      RoundingPolicy rounding,
      List<CalculationStep> steps) {}

  public record RoundingPolicy(
      int currencyScale, RoundingMode mode, RoundingStage appliedAt) {}

  public record CalculationStep(String name, String formula, long resultMinorUnits) {}

  public record MoneyAmount(
      long minorUnits, Currency currency, Precision precision, String source) {}

  public record MarketAssessment(
      MoneyAmount low,
      MoneyAmount high,
      Confidence confidence,
      Instant dataAsOf,
      String source,
      String providerSnapshotVersion) {}

  public enum ReviewStatus {
    MATCHES_APPROVED_BASELINE,
    REVIEW_REQUIRED,
    UNABLE_TO_VERIFY
  }
  public enum DealRating { FAIR_NEGOTIATE, POOR_DEAL }
  public enum Currency { USD }
  public enum Precision { EXACT, ESTIMATED, UNAVAILABLE }
  public enum Confidence { LOW, MEDIUM, HIGH }
  public enum RoundingMode { UNNECESSARY }
  public enum RoundingStage { INPUT_AND_FINAL_TOTAL }
}
