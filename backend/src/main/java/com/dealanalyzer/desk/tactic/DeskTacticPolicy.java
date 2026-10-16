package com.dealanalyzer.desk.tactic;

import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.trade.desk.TradeDeskCheck;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;

/** Pure documentary rules. No model, valuation, trade equity calculation or persistence. */
public final class DeskTacticPolicy {
  public static final String VERSION = "desk-tactics.v3";
  public static final long PAYMENT_TOLERANCE_MINOR = 100;
  public static final String PAYMENT_QUESTION = "Please show how this payment follows from the price, amount financed, APR, term and down payment, including any products in it.";
  public static final String TERM_QUESTION = "Please show the payment and total interest at my shorter term, with the same amount financed and APR.";
  public static final String APR_QUESTION = "Please show the payment and total interest using my own lender's APR, with the same amount financed and term.";
  public static final String DEALER_APR_QUESTION = "Please confirm the contract will show this APR, term and amount financed, with no products added to the loan.";
  /** Not a tactic: the dealer's financing costs less than the Buyer's own offer, and the Desk Check says so (spec 5.10). */
  public static final String DEALER_FINANCING_COSTS_LESS = "DEALER_FINANCING_COSTS_LESS";
  public static final String EQUITY_QUESTION = "Please show where the amount still owed on my trade is included in the loan, and its payment and interest effect.";
  public static final String ALLOWANCE_QUESTION = "Please show the trade allowance with the selling price and discount we agreed, so I can compare their combined effect.";

  public record BuyerOffer(BigDecimal aprPercent, Integer termMonths, JsonNode evidence) {}
  public record Input(JsonNode terms, BuyerOffer buyerOffer) {}
  /** Phase 2 can supply rules closing over ticket 103's result record; this policy never guesses its type. */
  @FunctionalInterface public interface Rule { Optional<Finding> evaluate(Input input); }
  public record Evidence(String field, JsonNode region, boolean verified) {}
  public record Finding(String code, String label, String summary, List<Evidence> evidenceRegions,
      Long monthlyEffectMinor, Long totalEffectMinor, String effectBasis, String noDollarEffectReason,
      String deskQuestion, String questionReview, String policyVersion, boolean verified, String tradeFindingCode) {}

  /** Names ticket 103 findings using its already computed result, without another provider or calculation. */
  public List<Finding> tradeFindings(Input input, TradeDeskCheck.Result trade, JsonNode source) {
    if (trade == null || !"CHECKED".equals(trade.status()) || trade.blocking()) return List.of();
    List<Finding> findings = new ArrayList<>();
    var negative = trade.findings().stream().filter(f -> "NEGATIVE_EQUITY_FINANCED".equals(f.code())).findFirst().orElse(null);
    if (negative != null && trade.negativeEquityFinancedMinor() != null && trade.negativeEquityFinancedMinor() > 0) {
      var cost = trade.negativeEquityCost();
      boolean available = cost != null && "AVAILABLE".equals(cost.status());
      String reason = available ? null : cost != null && cost.reason() != null ? cost.reason()
          : "The scheduled payment and interest effect is unavailable; the amount of negative equity is shown instead.";
      findings.add(tradeFinding(input, "NEGATIVE_EQUITY_FOLDED_IN", "Negative equity folded in", negative,
          List.of("TRADE_ALLOWANCE", "TRADE_PAYOFF", "AMOUNT_FINANCED", "APR", "TERM_MONTHS"),
          available ? cost.paymentIncreaseMinor() : null,
          available ? cost.scheduledPaymentsIncreaseMinor() : trade.negativeEquityFinancedMinor(),
          "From " + trade.policyVersion() + "; equity basis " + trade.equityBasis()
              + (available ? "; scheduled-payment effect includes principal and interest under " + cost.policyVersion()
                  : "; principal amount only; scheduled payments and interest unavailable"), reason, EQUITY_QUESTION, source));
    }
    var together = trade.priceTogether();
    var offset = trade.findings().stream().filter(f -> "ALLOWANCE_OFFSET_BY_PRICE".equals(f.code())).findFirst().orElse(null);
    if (offset != null && together != null && together.priceIncreaseMinor() != null && together.priceIncreaseMinor() > 0
        && together.allowanceAboveValueMinor() != null && together.allowanceAboveValueMinor() > 0) {
      findings.add(tradeFinding(input, "ALLOWANCE_PAID_FOR_BY_PRICE", "Allowance paid for by the price", offset,
          List.of("TRADE_ALLOWANCE", "SELLING_PRICE", "DISCOUNT_TOTAL"), null, together.netEffectMinor(),
          "Net trade-and-price effect from " + trade.policyVersion() + "; reference " + together.reference()
              + "; positive means the Buyer is ahead, negative means behind. This purchase-price comparison has no monthly effect.",
          together.netEffectMinor() == null ? "The combined trade-and-price dollar effect is unavailable in the trade check." : null,
          ALLOWANCE_QUESTION, source));
    }
    return List.copyOf(findings);
  }

  private Finding tradeFinding(Input input, String code, String label, TradeDeskCheck.Finding tradeFinding,
      List<String> fields, Long monthly, Long total, String basis, String reason, String question, JsonNode source) {
    var base = finding(input, code, label, tradeFinding.summary(), fields, monthly, total, basis, reason, question, false);
    var evidence = new ArrayList<>(base.evidenceRegions());
    for (JsonNode line : input.terms().path("lineItems")) if ("TRADE".equals(line.path("category").asText()))
      evidence.add(new Evidence("TRADE_LINE_ITEM", nullable(line.path("evidence")), line.path("buyerVerified").asBoolean(false)));
    boolean verified = !evidence.isEmpty() && evidence.stream().allMatch(Evidence::verified);
    evidence.add(new Evidence("TRADE_CHECK_RESULT", source, verified));
    return new Finding(code, label, base.summary(), List.copyOf(evidence), monthly, total, basis, reason, question,
        base.questionReview(), VERSION, verified, tradeFinding.code());
  }

  private final FinancingCalculator calculator = new FinancingCalculator();
  public List<Finding> evaluate(Input input) { return evaluate(input, List.of()); }
  public List<Finding> evaluate(Input input, List<Rule> additionalRules) {
    List<Finding> findings = new ArrayList<>();
    for (Rule rule : List.<Rule>of(this::paymentPacking, this::stretchedTerm, this::aprAboveOwn, this::dealerAprBelowOwn))
      rule.evaluate(input).ifPresent(findings::add);
    for (Rule rule : additionalRules) rule.evaluate(input).ifPresent(findings::add);
    return List.copyOf(findings);
  }

  private Optional<Finding> paymentPacking(Input input) {
    var principal = principal(input.terms());
    Long quoted = money(input.terms(), "MONTHLY_PAYMENT");
    Integer months = months(input.terms());
    var schedule = calculator.partialSchedule(principal.amount(), apr(input.terms()), months);
    if (quoted == null || schedule == null || quoted - schedule.paymentMinor() <= PAYMENT_TOLERANCE_MINOR)
      return Optional.empty();
    long delta = quoted - schedule.paymentMinor();
    var fields = new ArrayList<>(principal.fields());
    fields.addAll(List.of("APR", "TERM_MONTHS", "MONTHLY_PAYMENT"));
    return Optional.of(finding(input, "PAYMENT_PACKING", "Possible payment packing",
        "The quoted payment is " + dollars(delta) + " higher per month than the worksheet's own numbers produce. It may include products not shown; ask for a reconciliation.",
        fields, delta, Math.multiplyExact(delta, months.longValue()),
        principal.basis() + "; quoted monthly difference multiplied by term; actual final payment and timing may differ.", null, PAYMENT_QUESTION, false));
  }

  private Optional<Finding> stretchedTerm(Input input) {
    Integer dealer = months(input.terms());
    Integer buyer = input.buyerOffer() == null ? null : input.buyerOffer().termMonths();
    if (dealer == null || dealer < 1 || dealer > 1200 || buyer == null || buyer < 1 || buyer > 1200 || dealer <= buyer)
      return Optional.empty();
    var principal = principal(input.terms());
    var longLoan = calculator.partialSchedule(principal.amount(), apr(input.terms()), dealer);
    var shortLoan = calculator.partialSchedule(principal.amount(), apr(input.terms()), buyer);
    boolean available = longLoan != null && shortLoan != null;
    var fields = new ArrayList<>(principal.fields()); fields.addAll(List.of("APR", "TERM_MONTHS"));
    return Optional.of(finding(input, "STRETCHED_TERM", "Stretched term",
        "The worksheet's " + dealer + " month term is longer than your stated " + buyer + " months."
            + (available ? " The longer term lowers the monthly payment by " + dollars(shortLoan.paymentMinor() - longLoan.paymentMinor())
                + " and adds " + dollars(longLoan.interestMinor() - shortLoan.interestMinor()) + " in scheduled interest." : ""),
        fields, available ? longLoan.paymentMinor() - shortLoan.paymentMinor() : null,
        available ? longLoan.interestMinor() - shortLoan.interestMinor() : null,
        principal.basis() + "; same principal and APR at each term, including final-payment rounding.",
        available ? null : "Amount financed or APR is missing or outside the supported ordinary monthly schedule.", TERM_QUESTION, true));
  }

  private Optional<Finding> aprAboveOwn(Input input) {
    BigDecimal dealer = apr(input.terms()), own = input.buyerOffer() == null ? null : input.buyerOffer().aprPercent();
    if (!validApr(dealer) || !validApr(own) || dealer.compareTo(own) <= 0) return Optional.empty();
    var principal = principal(input.terms());
    var high = calculator.partialSchedule(principal.amount(), dealer, months(input.terms()));
    var low = calculator.partialSchedule(principal.amount(), own, months(input.terms()));
    boolean available = high != null && low != null;
    var fields = new ArrayList<>(principal.fields()); fields.addAll(List.of("APR", "TERM_MONTHS"));
    return Optional.of(finding(input, "APR_ABOVE_OWN_OFFER", "APR above your own offer",
        "The dealer APR of " + dealer.toPlainString() + "% is above your own offer's " + own.toPlainString() + "%."
            + (available ? " At the same amount financed and term, this adds " + dollars(high.interestMinor() - low.interestMinor()) + " in scheduled interest." : ""),
        fields, available ? high.paymentMinor() - low.paymentMinor() : null,
        available ? high.interestMinor() - low.interestMinor() : null,
        principal.basis() + "; APR-only comparison at the worksheet term, excluding differences in lender fees and timing.",
        available ? null : "Amount financed or term is missing or outside the supported ordinary monthly schedule.", APR_QUESTION, true));
  }

  /**
   * The dealer's APR is below the Buyer's own offer, so at the worksheet's amount financed and term the dealer's
   * loan costs less. Effects are negative: the Buyer pays less with the dealer's financing. Nothing about what any
   * lender pays otdog reaches this rule (PAY-01).
   */
  private Optional<Finding> dealerAprBelowOwn(Input input) {
    BigDecimal dealer = apr(input.terms()), own = input.buyerOffer() == null ? null : input.buyerOffer().aprPercent();
    if (!validApr(dealer) || !validApr(own) || dealer.compareTo(own) >= 0) return Optional.empty();
    var principal = principal(input.terms());
    var low = calculator.partialSchedule(principal.amount(), dealer, months(input.terms()));
    var high = calculator.partialSchedule(principal.amount(), own, months(input.terms()));
    boolean available = high != null && low != null;
    var fields = new ArrayList<>(principal.fields()); fields.addAll(List.of("APR", "TERM_MONTHS"));
    return Optional.of(finding(input, DEALER_FINANCING_COSTS_LESS, "Dealer financing costs less",
        "The dealer's financing costs less than your own offer: its APR of " + dealer.toPlainString() + "% is below your own offer's "
            + own.toPlainString() + "%."
            + (available ? " At the same amount financed and term, it is " + dollars(high.interestMinor() - low.interestMinor())
                + " less in scheduled interest." : ""),
        fields, available ? low.paymentMinor() - high.paymentMinor() : null,
        available ? low.interestMinor() - high.interestMinor() : null,
        principal.basis() + "; APR-only comparison at the worksheet term, excluding differences in lender fees and timing. "
            + "Negative amounts are what the dealer's financing costs less than your own offer.",
        available ? null : "Amount financed or term is missing or outside the supported ordinary monthly schedule.", DEALER_APR_QUESTION, true));
  }

  private Finding finding(Input input, String code, String label, String summary, List<String> fields,
      Long monthly, Long total, String basis, String reason, String question, boolean buyerEvidence) {
    List<Evidence> evidence = new ArrayList<>();
    for (String field : fields) {
      JsonNode term = term(input.terms(), field);
      if (term != null) evidence.add(new Evidence(field, nullable(term.path("evidence")), term.path("buyerVerified").asBoolean(false)));
    }
    if (buyerEvidence) evidence.add(new Evidence("BUYER_FINANCING_OFFER", input.buyerOffer().evidence(), true));
    return new Finding(code, label, summary, List.copyOf(evidence), monthly, total, basis, reason, question,
        "PENDING_OPERATOR_REVIEW", VERSION, !evidence.isEmpty() && evidence.stream().allMatch(Evidence::verified), null);
  }
  private record Principal(Long amount, List<String> fields, String basis) {}
  private Principal principal(JsonNode terms) {
    Long explicit = money(terms, "AMOUNT_FINANCED");
    if (explicit != null) return new Principal(explicit, List.of("AMOUNT_FINANCED"), "Worksheet amount financed; ordinary monthly payments starting one month after funding");
    // A price-minus-down comparison is documentary only. Never silently calculate trade equity.
    Long price = money(terms, "SELLING_PRICE"), down = money(terms, "CASH_DOWN");
    if (!present(terms, "AMOUNT_FINANCED") && !present(terms, "TRADE_ALLOWANCE") && !present(terms, "TRADE_PAYOFF")
        && price != null && down != null && price >= down)
      return new Principal(price - down, List.of("SELLING_PRICE", "CASH_DOWN"),
          "Worksheet selling price less stated cash down; excludes taxes, fees and products outside those figures; ordinary monthly schedule assumed");
    return new Principal(null, List.of(), "Worksheet principal unavailable; no trade or missing amounts inferred");
  }
  private static JsonNode term(JsonNode terms, String field) {
    if (terms == null) return null;
    JsonNode found = null;
    for (JsonNode node : terms.path("candidates")) if (field.equals(node.path("field").asText())) {
      if (found != null) return null; // Ambiguous duplicates are not chosen by array order.
      found = node;
    }
    return found;
  }
  private static boolean present(JsonNode terms, String field) {
    if (terms != null) for (JsonNode term : terms.path("candidates"))
      if (field.equals(term.path("field").asText())) return true;
    return false;
  }
  private static String value(JsonNode terms, String field, String unit) {
    JsonNode term = term(terms, field);
    if (term == null || !unit.equals(term.path("unit").asText()) || !term.path("value").isTextual()) return null;
    return term.path("value").asText();
  }
  private static Long money(JsonNode terms, String field) {
    try { long amount = Long.parseLong(value(terms, field, "USD_MINOR")); return amount >= 0 && amount <= 100_000_000_000L ? amount : null; }
    catch (NumberFormatException invalid) { return null; }
  }
  private static Integer months(JsonNode terms) {
    String value = value(terms, "TERM_MONTHS", "MONTH");
    if (value == null) value = value(terms, "TERM_MONTHS", "MONTHS");
    try { return Integer.valueOf(value); } catch (NumberFormatException invalid) { return null; }
  }
  private static BigDecimal apr(JsonNode terms) {
    String value = value(terms, "APR", "PERCENT");
    try { return value == null ? null : new BigDecimal(value); } catch (NumberFormatException invalid) { return null; }
  }
  private static boolean validApr(BigDecimal apr) { return apr != null && apr.signum() >= 0 && apr.compareTo(new BigDecimal("100")) <= 0 && apr.stripTrailingZeros().scale() <= 8; }
  private static JsonNode nullable(JsonNode node) { return node.isMissingNode() || node.isNull() ? null : node; }
  private static String dollars(long minor) { return "$" + BigDecimal.valueOf(minor, 2).toPlainString(); }
}
