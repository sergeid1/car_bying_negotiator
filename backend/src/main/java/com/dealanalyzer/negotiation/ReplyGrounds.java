package com.dealanalyzer.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a reply may rely on, read only from one finalized report: recorded facts a reply may state
 * (each with its report pointer and a product-owned sentence) and the Buyer-approved counter figure
 * and ceiling. It never reads document labels, provider prose or the Dealer's answer.
 *
 * @param boundary null when the report has no approved baseline or its limits cannot bound a
 *     selling-price counter, in which case no reply may propose a figure
 */
public record ReplyGrounds(int reportVersion, String dealerOfferId, Integer dealerOfferVersion,
    List<Fact> facts, Boundary boundary) {
  public ReplyGrounds { facts = List.copyOf(facts); }

  /** One recorded fact. {@code statement} is the only wording a reply may use to state it. */
  public record Fact(String id, String sourcePointer, String sourceValue, String statement) {}

  /**
   * The approved selling-price boundary. {@code ceilingMinor} is the highest counter a reply may
   * propose: the approved walk-away limit, lowered when needed so the resulting out-the-door total
   * also stays within the approved out-the-door walk-away limit.
   */
  public record Boundary(String currency, long targetMinor, long ceilingMinor, long sellingWalkAwayMinor,
      Long outTheDoorWalkAwayMinor) {}

  public Fact fact(String id) {
    return facts.stream().filter(fact -> fact.id().equals(id)).findFirst().orElse(null);
  }

  /** The Dealer's current selling price, when the report records one reliably. */
  public Long currentSellingPriceMinor() {
    Fact fact = fact(SELLING_PRICE);
    return fact == null ? null : Long.valueOf(fact.sourceValue());
  }

  static final String SELLING_PRICE = "offer-selling-price";
  static final String OUT_THE_DOOR = "offer-out-the-door-total";
  static final String MARKET_RANGE = "market-selling-price-range";
  static final String PAYMENT_MISMATCH = "payment-reconciliation";

  public static ReplyGrounds from(JsonNode report) {
    if (!"core-deal-analysis-report.v1".equals(report.path("schemaVersion").asText()))
      throw new IllegalArgumentException("Finalized core report required");
    List<Fact> facts = new ArrayList<>();
    JsonNode analysis = report.path("analysis");
    if ("AVAILABLE".equals(analysis.path("cashAvailability").asText())) {
      JsonNode result = report.at("/evidence/cashEconomics/result");
      JsonNode lines = result.path("lineItems");
      int sellingIndex = -1;
      for (int index = 0; index < lines.size(); index++) {
        if ("SELLING_PRICE".equals(lines.get(index).path("type").asText())) {
          // More than one selling-price line has no single recorded figure to state.
          sellingIndex = sellingIndex == -1 ? index : -2;
        }
      }
      if (sellingIndex >= 0) {
        amount(facts, report, SELLING_PRICE, "/evidence/cashEconomics/result/lineItems/" + sellingIndex + "/amount",
            "The current written offer lists a selling price of %s.");
      }
      amount(facts, report, OUT_THE_DOOR, "/evidence/cashEconomics/result/outTheDoorTotal",
          "The current written offer lists an out-the-door total of %s.");
    } else if ("AVAILABLE".equals(analysis.path("financingAvailability").asText())
        && "USD".equals(analysis.at("/financingTerms/currency").asText())) {
      minor(facts, report, SELLING_PRICE, "/analysis/financingTerms/purchasePriceMinor",
          "The current written offer lists a selling price of %s.");
      minor(facts, report, OUT_THE_DOOR, "/analysis/financingTerms/outTheDoorTotalMinor",
          "The current written offer lists an out-the-door total of %s.");
      JsonNode tolerance = analysis.at("/financing/withinTolerance");
      if (tolerance.isBoolean() && !tolerance.booleanValue()) {
        facts.add(new Fact(PAYMENT_MISMATCH, "/analysis/financing/withinTolerance", "false",
            "The quoted monthly payment does not match the payment calculated from the written loan terms."));
      }
    }
    if ("AVAILABLE".equals(analysis.path("marketAvailability").asText())) {
      JsonNode range = report.at("/evidence/marketAssessment/range");
      if ("USD".equals(range.path("currency").asText()) && range.path("lowMinor").canConvertToLong()
          && range.path("highMinor").canConvertToLong()) {
        facts.add(new Fact(MARKET_RANGE, "/evidence/marketAssessment/range", range.toString(),
            "Recorded market estimates for this vehicle range from " + dollars(range.path("lowMinor").longValue())
                + " to " + dollars(range.path("highMinor").longValue()) + " in selling price."));
      }
    }
    JsonNode offer = report.at("/evidence/dealerOffer");
    return new ReplyGrounds(report.path("version").asInt(),
        offer.path("id").isTextual() ? offer.path("id").asText() : null,
        offer.path("version").isInt() ? offer.path("version").asInt() : null,
        facts, boundary(report.at("/evidence/approvedBaseline/payload/plan")));
  }

  private static Boundary boundary(JsonNode plan) {
    if (plan.isMissingNode() || plan.isNull() || !"USD".equals(plan.path("currency").asText())) return null;
    JsonNode selling = plan.path("sellingPrice");
    if (!selling.path("targetMinor").canConvertToLong() || !selling.path("walkAwayMinor").canConvertToLong()) return null;
    long target = selling.path("targetMinor").longValue();
    long walkAway = selling.path("walkAwayMinor").longValue();
    long ceiling = walkAway;
    JsonNode otd = plan.path("outTheDoor");
    Long otdWalkAway = null;
    if (otd.isObject()) {
      if (!otd.path("targetMinor").canConvertToLong() || !otd.path("walkAwayMinor").canConvertToLong()) return null;
      otdWalkAway = otd.path("walkAwayMinor").longValue();
      // Lowering the selling price lowers the out-the-door total by at least as much (tax does not
      // fall below zero), so a counter this far below the target stays within the OTD limit. Above
      // the target the relation depends on tax, so the counter never exceeds the target.
      long gap = Math.subtractExact(otdWalkAway, otd.path("targetMinor").longValue());
      ceiling = Math.min(ceiling, Math.addExact(target, Math.min(0, gap)));
    } else if (plan.path("buyerMaxOutTheDoorPriceMinor").canConvertToLong()) {
      // An OTD limit with no recorded conversion cannot bound a selling-price counter.
      return null;
    }
    if (ceiling <= 0) return null;
    return new Boundary("USD", target, ceiling, walkAway, otdWalkAway);
  }

  private static void amount(List<Fact> facts, JsonNode report, String id, String pointer, String sentence) {
    JsonNode amount = report.at(pointer);
    if ("USD".equals(amount.path("currency").asText()) && amount.path("minorUnits").canConvertToLong()) {
      long value = amount.path("minorUnits").longValue();
      facts.add(new Fact(id, pointer + "/minorUnits", Long.toString(value), sentence.formatted(dollars(value))));
    }
  }

  private static void minor(List<Fact> facts, JsonNode report, String id, String pointer, String sentence) {
    JsonNode value = report.at(pointer);
    if (value.canConvertToLong() && value.isIntegralNumber()) {
      facts.add(new Fact(id, pointer, Long.toString(value.longValue()), sentence.formatted(dollars(value.longValue()))));
    }
  }

  /** US dollars from minor units, e.g. 3150000 as $31,500 and 3150025 as $31,500.25. */
  public static String dollars(long minor) {
    BigDecimal value = BigDecimal.valueOf(minor, 2);
    DecimalFormat format = new DecimalFormat(minor % 100 == 0 ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    return "$" + format.format(value);
  }
}
