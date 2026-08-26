package com.dealanalyzer.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DealAnalysis(
    String reviewStatus,
    String dealRating,
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
      String calculationVersion) {}

  public record MoneyAmount(long minorUnits, String currency, String precision, String source) {}

  public record MarketAssessment(
      MoneyAmount low,
      MoneyAmount high,
      String confidence,
      Instant dataAsOf,
      String source,
      String providerSnapshotVersion) {}
}

