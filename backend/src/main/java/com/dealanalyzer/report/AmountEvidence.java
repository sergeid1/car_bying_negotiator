package com.dealanalyzer.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains where each report amount came from: the dealer document page and region it was read
 * from, or the plain statement that no document source exists, and for calculated amounts the
 * inputs and operation as arithmetic a Buyer can repeat by hand. Reads only frozen evidence.
 */
public final class AmountEvidence {
  /** Where on a frozen dealer document an amount was read. Region values are page fractions. */
  public record DocumentSource(String documentId, String extractionId, Integer offerVersion,
      String wording, int pageNumber, Region region) {}
  public record Region(double x, double y, double width, double height) {}
  /** One term of the arithmetic; the first operand has no operator. */
  public record Operand(String operator, String label, Long minorUnits, String value, boolean paperworkLabel) {
    public Operand(String operator, String label, Long minorUnits, String value) {
      this(operator, label, minorUnits, value, false);
    }
  }
  public record Calculation(List<Operand> operands, List<String> notes) {}
  public record Explanation(DocumentSource documentSource, String sourceNote, Calculation calculation) {}

  private final Map<String, Explanation> explanations = new HashMap<>();

  public Explanation find(String source, String pointer) {
    return explanations.get(source + "#" + pointer);
  }

  /** Recorded cash calculation and the frozen Dealer Offer terms it was computed from. */
  public AmountEvidence cash(JsonNode result, JsonNode offerTerms, String documentId, Integer offerVersion) {
    if (result == null || result.isNull() || result.isMissingNode()) return this;
    String extractionId = offerTerms == null ? null : text(offerTerms.path("sourceExtractionId"));
    JsonNode lines = result.path("lineItems");
    for (int index = 0; index < lines.size(); index++) {
      JsonNode line = lines.get(index);
      DocumentSource source = documentSource(offerTerms, line.path("evidence").asText(), documentId, extractionId, offerVersion);
      put("cashEconomics", "/result/lineItems/" + index + "/amount", new Explanation(source,
          source == null ? "No document region is recorded for this line." : null, null));
    }
    String quotedId = result.at("/reconciliation/evidence").asText();
    DocumentSource quoted = documentSource(offerTerms, quotedId, documentId, extractionId, offerVersion);
    put("cashEconomics", "/result/reconciliation/quotedTotal", new Explanation(quoted,
        quoted == null ? "The Dealer Offer has no quoted out-the-door total on a document." : null, null));

    calculated("/result/vehicleSubtotal", sumLines(lines, Set.of("SELLING_PRICE"), Set.of("DEALER_DISCOUNT"), true));
    calculated("/result/dealerCharges", sumLines(lines, Set.of("DEALER_FEE", "ADD_ON"), Set.of(), false));
    calculated("/result/governmentCharges", sumLines(lines, Set.of("GOVERNMENT_CHARGE"), Set.of(), false));
    calculated("/result/unknownCharges", sumLines(lines, Set.of("UNKNOWN"), Set.of(), false));
    calculated("/result/tradeTerms/allowance", sumLines(lines, Set.of("TRADE_ALLOWANCE"), Set.of(), false));
    calculated("/result/tradeTerms/payoff", sumLines(lines, Set.of("TRADE_PAYOFF"), Set.of(), false));
    calculated("/result/downPayment", sumLines(lines, Set.of("DOWN_PAYMENT"), Set.of(), false));
    calculated("/result/credits", sumLines(lines, Set.of("OUTSIDE_FINANCING", "OTHER_BUYER_FUNDS", "CREDIT"), Set.of(), false));
    calculated("/result/priceReductions/dealerDiscounts", sumLines(lines, Set.of("DEALER_DISCOUNT"), Set.of(), false));
    calculated("/result/priceReductions/verifiedRebates", rebates(lines, "VERIFIED"));
    calculated("/result/priceReductions/conditionalRebatesExcluded", rebates(lines, "UNVERIFIED"));
    // The remaining price-reduction field is the combined total of discounts and verified rebates.
    result.path("priceReductions").fieldNames().forEachRemaining(field -> {
      if (!Set.of("dealerDiscounts", "verifiedRebates", "conditionalRebatesExcluded").contains(field))
        calculated("/result/priceReductions/" + field, new Calculation(List.of(
            money(null, "Dealer discounts", result.at("/priceReductions/dealerDiscounts")),
            money("+", "Verified rebates", result.at("/priceReductions/verifiedRebates"))), List.of()));
    });
    calculated("/result/taxes", tax(result));
    Calculation otd = new Calculation(List.of(
        money(null, "Vehicle subtotal", result.path("vehicleSubtotal")),
        money("+", "Dealer charges", result.path("dealerCharges")),
        money("+", "Government charges", result.path("governmentCharges")),
        money("+", "Unclassified charges", result.path("unknownCharges")),
        money("+", "Taxes", result.path("taxes"))), List.of());
    calculated("/result/outTheDoorTotal", otd);
    calculated("/result/reconciliation/recomputedTotal", otd);
    calculated("/result/tradeTerms/netCredit", new Calculation(List.of(
        money(null, "Trade allowance", result.at("/tradeTerms/allowance")),
        money("−", "Trade payoff", result.at("/tradeTerms/payoff"))), List.of()));
    calculated("/result/netAmountDue", new Calculation(List.of(
        money(null, "Out-the-door total", result.path("outTheDoorTotal")),
        money("−", "Net trade credit", result.at("/tradeTerms/netCredit")),
        money("−", "Down payment", result.path("downPayment")),
        money("−", "Credits and other Buyer funds", result.path("credits"))), List.of()));
    if (result.at("/reconciliation/quotedTotal").isObject())
      calculated("/result/reconciliation/discrepancy", new Calculation(List.of(
          money(null, "Dealer's quoted out-the-door total", result.at("/reconciliation/quotedTotal")),
          money("−", "Recomputed out-the-door total", result.at("/reconciliation/recomputedTotal"))), List.of()));
    else put("cashEconomics", "/result/reconciliation/discrepancy", new Explanation(null,
        "No quoted total exists to compare against, so this difference is recorded as zero.", null));
    put("cashEconomics", "/result/reconciliation/tolerance", new Explanation(null,
        "Policy setting from " + result.path("policyVersion").asText("the jurisdiction policy")
            + "; not read from a document.", null));
    return this;
  }

  /** Buyer-confirmed financing inputs and the independently calculated schedule. */
  public AmountEvidence financing(JsonNode inputs, JsonNode result) {
    if (inputs == null || inputs.isNull() || inputs.isMissingNode()) return this;
    String inputNote = "Buyer-confirmed financing entry; no document region is recorded for it.";
    inputs.fieldNames().forEachRemaining(field -> {
      if (field.endsWith("Minor")) put("financing", "/inputs/" + field, new Explanation(null, inputNote, null));
    });
    put("financing", "/inputs/principalMinor", new Explanation(null, inputNote, new Calculation(List.of(
        minor(null, "Net amount due", inputs.path("netAmountDueMinor")),
        minor("+", "Financed lender fees", inputs.path("financedFeesMinor"))), List.of())));
    if (result == null || !result.path("paymentMinor").isIntegralNumber()) return this;
    String apr = inputs.path("aprPercent").isNumber() ? inputs.path("aprPercent").decimalValue().stripTrailingZeros().toPlainString() + "%" : "Unknown";
    String term = inputs.path("termMonths").asText("Unknown");
    financed("/result/paymentMinor", new Calculation(List.of(
        minor(null, "Amount financed", inputs.path("principalMinor")),
        new Operand("at", "Annual percentage rate", null, apr),
        new Operand("over", "Months", null, term)), List.of(
        "Monthly rate r = APR ÷ 12. Payment = amount financed × r ÷ (1 − (1 + r) to the power of −months), rounded to the cent.")));
    int months = inputs.path("termMonths").asInt(0);
    financed("/result/finalPaymentMinor", new Calculation(List.of(
        minor(null, "Amount financed", inputs.path("principalMinor")),
        new Operand("at", "Annual percentage rate", null, apr),
        new Operand("less", "Regular monthly payments of " + cents(result.path("paymentMinor")), null,
            months > 0 ? Integer.toString(months - 1) : "Unknown")), List.of(
        "The final payment is the balance those regular payments leave plus one month of interest on it, rounded to the cent.")));
    financed("/result/totalScheduledPaymentsMinor", new Calculation(List.of(
        minor(null, "Monthly payment", result.path("paymentMinor")),
        new Operand("×", "Regular payments", null, months > 0 ? Integer.toString(months - 1) : "Unknown"),
        minor("+", "Final payment", result.path("finalPaymentMinor"))), List.of()));
    financed("/result/totalInterestMinor", new Calculation(List.of(
        minor(null, "Total scheduled payments", result.path("totalScheduledPaymentsMinor")),
        minor("−", "Amount financed", inputs.path("principalMinor"))), List.of()));
    financed("/result/totalTransactionCostMinor", new Calculation(List.of(
        minor(null, "Cash paid at or before closing", inputs.path("cashPaidAtClosingMinor")),
        minor("+", "Total scheduled payments", result.path("totalScheduledPaymentsMinor")),
        minor("+", "Known deferred charges", inputs.path("deferredChargesMinor"))), List.of()));
    return this;
  }

  /** Market provider estimates carry no dealer document. */
  public AmountEvidence market(String provider) {
    String note = "Market estimate from " + (provider == null ? "the market provider" : provider)
        + "; not read from a dealer document.";
    for (String pointer : List.of("/range/lowMinor", "/range/highMinor", ""))
      put("marketAssessment", pointer, new Explanation(null, note, null));
    return this;
  }

  /** Approved plan amounts: Buyer limits as entered, boundaries as calculated from the plan's own values. */
  public AmountEvidence plan(JsonNode plan, JsonNode approvedCash, Integer baselineVersion) {
    if (plan == null || plan.isNull() || plan.isMissingNode()) return this;
    String base = "/payload/plan";
    String approved = "Approved Baseline" + (baselineVersion == null ? "" : " version " + baselineVersion);
    String buyer = "Limit you entered in the " + approved + "; not read from a dealer document.";
    put("approvedBaseline", base + "/buyerMaxSellingPriceMinor", new Explanation(null, buyer, null));
    put("approvedBaseline", base + "/buyerMaxOutTheDoorPriceMinor", new Explanation(null, buyer, null));
    String planNote = "Calculated in the " + approved + "; not read from a dealer document.";
    for (String boundary : List.of("sellingPrice", "outTheDoor")) {
      JsonNode values = plan.path(boundary);
      if (!values.isObject()) continue;
      String name = boundary.equals("sellingPrice") ? "selling price" : "out-the-door";
      String limit = boundary.equals("sellingPrice") ? "buyerMaxSellingPriceMinor" : "buyerMaxOutTheDoorPriceMinor";
      String path = base + "/" + boundary;
      if (boundary.equals("sellingPrice")) {
        planned(path + "/openingMinor", planNote, new Calculation(List.of(
            minor(null, "Market selling-price low", values.path("openingMinor"))), List.of()));
        planned(path + "/policyMaximumMinor", planNote, new Calculation(List.of(
            minor(null, "Market selling-price high", values.path("policyMaximumMinor"))), List.of()));
        planned(path + "/targetMinor", planNote, new Calculation(List.of(
            minor(null, "Opening " + name, values.path("openingMinor")),
            new Operand("+", "Half of (maximum − opening), rounded down to the cent", halfSpread(values), null)), List.of()));
      } else for (String field : List.of("openingMinor", "targetMinor", "policyMaximumMinor"))
        planned(path + "/" + field, planNote, otdConversion(plan, approvedCash, field));
      List<String> walkNotes = plan.path(limit).isIntegralNumber()
          ? List.of("Walk-away is the lower of the policy maximum and your limit.")
          : List.of("No Buyer limit was entered, so walk-away equals the policy maximum.");
      List<Operand> walk = new ArrayList<>(List.of(minor(null, "Policy maximum " + name, values.path("policyMaximumMinor"))));
      if (plan.path(limit).isIntegralNumber()) walk.add(minor("lower of", "Your " + name + " limit", plan.path(limit)));
      planned(path + "/walkAwayMinor", planNote, new Calculation(walk, walkNotes));
    }
    JsonNode savings = plan.path("savings");
    if (savings.isObject()) {
      planned(base + "/savings/baselineMinor", planNote, new Calculation(List.of(
          minor(null, "Selling price in the Dealer Offer", savings.path("baselineMinor"))), List.of()));
      planned(base + "/savings/amountMinor", planNote, new Calculation(List.of(
          minor(null, "Selling price in the Dealer Offer", savings.path("baselineMinor")),
          minor("−", "Target selling price", plan.at("/sellingPrice/targetMinor"))), List.of(
          "A negative result is shown as zero. Negotiation outcomes are uncertain.")));
    }
    return this;
  }

  private Calculation otdConversion(JsonNode plan, JsonNode cash, String field) {
    JsonNode boundary = plan.at("/sellingPrice/" + field), converted = plan.at("/outTheDoor/" + field);
    JsonNode recorded = cash == null ? null : cash.at("/outTheDoorTotal/minorUnits");
    Long change = boundary.isIntegralNumber() && cash != null ? boundary.asLong() - sellingPrice(cash) : null;
    Long taxChange = change != null && converted.isIntegralNumber() && recorded != null && recorded.isIntegralNumber()
        ? converted.asLong() - recorded.asLong() - change : null;
    return new Calculation(List.of(
        minor(null, "Recorded out-the-door total", recorded),
        new Operand("+", "Selling-price change (boundary − recorded selling price)", change, null),
        new Operand("+", "Tax change on that selling-price change", taxChange, null)), List.of(
        "Other offer terms are held fixed; the tax change is the tax recalculated at the recorded rate minus the recorded tax."));
  }

  private static Long halfSpread(JsonNode values) {
    JsonNode low = values.path("openingMinor"), high = values.path("policyMaximumMinor");
    return low.isIntegralNumber() && high.isIntegralNumber() ? (high.asLong() - low.asLong()) / 2 : null;
  }

  private static long sellingPrice(JsonNode cash) {
    long total = 0;
    if (cash != null) for (JsonNode line : cash.path("lineItems"))
      if (line.path("type").asText().equals("SELLING_PRICE")) total += line.at("/amount/minorUnits").asLong();
    return total;
  }

  private static Calculation sumLines(JsonNode lines, Set<String> added, Set<String> subtracted, boolean subtractVerifiedRebates) {
    List<Operand> operands = new ArrayList<>();
    for (boolean adding : List.of(true, false))
      for (JsonNode line : lines) {
        String type = line.path("type").asText();
        boolean rebate = subtractVerifiedRebates && type.equals("REBATE") && line.path("eligibility").asText().equals("VERIFIED");
        boolean matches = adding ? added.contains(type) : subtracted.contains(type) || rebate;
        if (matches) operands.add(quotedMoney(adding ? operands.isEmpty() ? null : "+" : "−",
            line.path("label").asText(), line.path("amount")));
      }
    return new Calculation(operands, operands.isEmpty() ? List.of("No Dealer Offer line contributes, so the amount is zero.") : List.of());
  }

  private static Calculation rebates(JsonNode lines, String eligibility) {
    List<Operand> operands = new ArrayList<>();
    for (JsonNode line : lines)
      if (line.path("type").asText().equals("REBATE") && line.path("eligibility").asText().equals(eligibility))
        operands.add(quotedMoney(operands.isEmpty() ? null : "+", line.path("label").asText(), line.path("amount")));
    return new Calculation(operands, operands.isEmpty() ? List.of("No Dealer Offer line contributes, so the amount is zero.") : List.of());
  }

  private static Calculation tax(JsonNode result) {
    JsonNode tax = result.path("taxCalculation");
    if (!tax.isObject()) return null;
    if (com.dealanalyzer.economics.CashDealEconomics.TaxCalculation.NOT_CHECKED.equals(tax.path("status").asText()))
      return new Calculation(List.of(major(null, "Dealer's tax as written", tax.path("unrounded"))),
          List.of(com.dealanalyzer.economics.CrossStatePurchase.TAX_NOT_CHECKED));
    BigDecimal rate = tax.path("rate").decimalValue();
    String currency = result.at("/outTheDoorTotal/currency").asText("USD");
    if (tax.path("components").isArray() && !tax.path("components").isEmpty()) {
      List<Operand> operands = new ArrayList<>();
      List<String> notes = new ArrayList<>();
      BigDecimal basis = tax.path("taxableBasis").decimalValue();
      for (JsonNode component : tax.path("components")) {
        BigDecimal componentBasis = component.path("baseCap").isNumber()
            ? basis.min(component.path("baseCap").decimalValue()) : basis;
        BigDecimal componentRate = component.path("rate").decimalValue();
        String name = component.path("name").asText();
        String arithmetic = componentBasis.toPlainString() + " " + currency + " × "
            + componentRate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
        operands.add(new Operand(operands.isEmpty() ? null : "+", name + ": " + arithmetic, null,
            componentBasis.multiply(componentRate).stripTrailingZeros().toPlainString() + " " + currency));
        notes.add(name + ": " + component.path("citation").asText().split(" https://", 2)[0]
            + " (checked " + component.path("checkedOn").asText() + ").");
      }
      notes.add("Each component uses the taxable price after the trade-in allowance, limited by its recorded base cap when present.");
      notes.add("Unrounded tax " + tax.path("unrounded").decimalValue().toPlainString() + " " + currency
          + ", rounded " + result.at("/rounding/mode").asText("HALF_UP").replace('_', ' ').toLowerCase()
          + " to " + result.at("/rounding/currencyScale").asInt(2) + " decimal places.");
      return new Calculation(operands, notes);
    }
    return new Calculation(List.of(
        major(null, "Taxable basis", tax.path("taxableBasis")),
        new Operand("×", "Tax rate", null, rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%")), List.of(
        "Taxable basis = taxable charges " + plain(tax.path("taxableCharges"), currency)
            + " − trade tax credit applied " + plain(tax.path("tradeCreditApplied"), currency) + ".",
        "Unrounded tax " + tax.path("unrounded").decimalValue().toPlainString() + " " + currency + ", rounded "
            + result.at("/rounding/mode").asText("HALF_UP").replace('_', ' ').toLowerCase() + " to "
            + result.at("/rounding/currencyScale").asInt(2) + " decimal places."));
  }

  private static String plain(JsonNode value, String currency) {
    return value.isNumber() ? value.decimalValue().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString() + " " + currency : "unknown";
  }

  private static String cents(JsonNode minor) {
    return minor.isIntegralNumber() ? BigDecimal.valueOf(minor.asLong(), 2).toPlainString() + " USD" : "unknown";
  }

  private static DocumentSource documentSource(JsonNode terms, String id, String documentId, String extractionId, Integer offerVersion) {
    if (terms == null || id == null || id.isBlank()) return null;
    for (String collection : List.of("lineItems", "candidates"))
      for (JsonNode term : terms.path(collection)) {
        String termId = term.path(collection.equals("lineItems") ? "sourceLineItemId" : "sourceCandidateId").asText(term.path("id").asText());
        JsonNode region = term.path("evidence");
        if (!termId.equals(id) || !region.path("pageNumber").isIntegralNumber()) continue;
        String wording = text(term.path(collection.equals("lineItems") ? "originalLabel" : "originalWording"));
        return new DocumentSource(documentId, extractionId, offerVersion, wording, region.path("pageNumber").asInt(),
            new Region(region.path("x").asDouble(), region.path("y").asDouble(),
                region.path("width").asDouble(), region.path("height").asDouble()));
      }
    return null;
  }

  private void calculated(String pointer, Calculation calculation) {
    put("cashEconomics", pointer, new Explanation(null,
        "Calculated from the inputs below; no single document line holds this amount.", calculation));
  }

  private void financed(String pointer, Calculation calculation) {
    put("financing", pointer, new Explanation(null,
        "Calculated independently from your confirmed financing entry; not read from a document.", calculation));
  }

  private void planned(String pointer, String note, Calculation calculation) {
    put("approvedBaseline", pointer, new Explanation(null, note, calculation));
  }

  private void put(String source, String pointer, Explanation explanation) {
    explanations.put(source + "#" + pointer, explanation);
  }

  private static Operand money(String operator, String label, JsonNode amount) {
    return new Operand(operator, label, amount.path("minorUnits").isIntegralNumber() ? amount.path("minorUnits").asLong() : null, null);
  }
  private static Operand quotedMoney(String operator, String label, JsonNode amount) {
    var value = money(operator, label, amount);
    return new Operand(value.operator(), value.label(), value.minorUnits(), value.value(), true);
  }

  private static Operand minor(String operator, String label, JsonNode value) {
    return new Operand(operator, label, value != null && value.isIntegralNumber() ? value.asLong() : null, null);
  }

  private static Operand major(String operator, String label, JsonNode value) {
    if (!value.isNumber()) return new Operand(operator, label, null, null);
    BigDecimal minor = value.decimalValue().movePointRight(2);
    return minor.stripTrailingZeros().scale() <= 0
        ? new Operand(operator, label, minor.longValueExact(), null)
        : new Operand(operator, label, null, value.decimalValue().toPlainString());
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }
}
