package com.dealanalyzer.report;

import com.fasterxml.jackson.databind.JsonNode;

/** Selects a recorded total; never calculates or substitutes an approved price boundary. */
public final class ReportLead {
  private ReportLead() {}
  public static JsonNode total(JsonNode payload) {
    for (var amount : payload.at("/analysis/materialAmounts"))
      if (java.util.List.of("/result/outTheDoorTotal", "/inputs/outTheDoorTotalMinor", "/analysis/economics/outTheDoorTotal")
          .contains(amount.path("pointer").asText())) return amount;
    return com.fasterxml.jackson.databind.node.NullNode.getInstance();
  }
  public static boolean known(JsonNode amount) {
    return amount.path("minorUnits").isIntegralNumber() && !amount.path("precision").asText().equals("UNAVAILABLE");
  }
  public static String value(JsonNode amount) {
    return known(amount) ? java.math.BigDecimal.valueOf(amount.path("minorUnits").asLong(), 2).toPlainString()
        + " " + amount.path("currency").asText() : "Unknown";
  }
}
