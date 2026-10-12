package com.dealanalyzer.trade.desk;

import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.trade.TradeService;
import com.dealanalyzer.trade.valuation.TradeValuationAdapter;
import com.dealanalyzer.trade.valuation.TradeValuationService;
import com.dealanalyzer.trade.valuation.TradeVehicle;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Checks the trade on desk paperwork against what the Buyer recorded (spec 5.9, TRADE-04, TRADE-05):
 * the allowance against the best unexpired written offer and a licensed valuation range (or, without
 * one, the Buyer's own most recent estimate from a site such as Carfax, ticket 106), the
 * paperwork's payoff against the Buyer's, the trade's equity, and the trade VIN. A shortfall is
 * shown in dollars and never counted as a dealer discount (DEAL-09); an allowance above the trade's
 * value is shown next to any price increase, never netted against it silently.
 *
 * <p>The caller has authorized the case. Terms use the verified-offer shape: candidates with a
 * field and value, and line items with a category and amount. Nothing here is stored.
 */
@Service
public class TradeDeskCheck {
  public static final String VERSION = "trade-desk-check.v2";
  private static final List<String> LOAN = List.of("AMOUNT_FINANCED", "APR", "TERM_MONTHS", "MONTHLY_PAYMENT");
  /** Lead order inside the TRADE group; informational lines follow. */
  private static final List<String> ORDER = List.of("TRADE_VIN_DIFFERENT", "BELOW_WRITTEN_OFFER", "BELOW_VALUATION_RANGE",
      "PAYOFF_DIFFERS", "PAYOFF_NOT_OWED", "PAYOFF_STALE", "PAYOFF_UNKNOWN", "PAYOFF_NOT_SHOWN",
      "NEGATIVE_EQUITY_FINANCED", "NEGATIVE_EQUITY", "ALLOWANCE_OFFSET_BY_PRICE", "ALLOWANCE_ABOVE_VALUE",
      "TRADE_NOT_RECORDED", "AT_OR_ABOVE_WRITTEN_OFFER", "WITHIN_VALUATION_RANGE", "ABOVE_VALUATION_RANGE",
      "VALUATION_UNAVAILABLE", "PAYOFF_SAME", "EQUITY", "TRADE_VIN_SAME", "TRADE_VIN_NOT_SHOWN");
  private static final Duration VALUATION_REUSE = Duration.ofMinutes(10);
  private static final int VALUATION_CACHE_LIMIT = 1_000;

  /** NO_ALLOWANCE (the paperwork shows no trade allowance), TRADE_NOT_RECORDED, or CHECKED. */
  public record Result(String policyVersion, String status, String currency, Long allowanceMinor,
      Long paperworkPayoffMinor, BuyerPayoff buyerPayoff, Long payoffDifferenceMinor, Long equityMinor,
      String equityBasis, Long negativeEquityFinancedMinor, NegativeEquityCost negativeEquityCost,
      BestOffer bestOffer, Long shortfallToOfferMinor, TaxCredit taxCredit, Long offerGapAfterTaxMinor,
      Valuation valuation, VinMatch vinMatch, PriceTogether priceTogether, List<Finding> findings) {
    /** Warnings and blocking findings, in lead order; informational lines are left out. */
    public List<Finding> leading() {
      return findings.stream().filter(finding -> !"INFORMATIONAL".equals(finding.severity())).toList();
    }
    public boolean blocking() { return findings.stream().anyMatch(finding -> "BLOCKING".equals(finding.severity())); }
  }
  /** state: CURRENT, STALE (past its good-through date), UNKNOWN (unanswered or "I'm not sure") or NOT_OWED. */
  public record BuyerPayoff(String state, Long amountMinor, String goodThrough) {}
  /** status: AVAILABLE or UNAVAILABLE with a reason. Increases are from the negative equity alone. */
  public record NegativeEquityCost(String status, String reason, Long amountFinancedMinor, BigDecimal aprPercent,
      Integer termMonths, Long paymentIncreaseMinor, Long scheduledPaymentsIncreaseMinor, Long interestMinor,
      String policyVersion) {}
  public record BestOffer(String id, long amountMinor, String source, String offerDate, String expiresOn, String label) {}
  /**
   * The sales tax a trade-in removes under the case's jurisdiction policy, which selling the car elsewhere
   * would not. status: AVAILABLE, NOT_APPLICABLE (the policy gives no trade credit) or UNAVAILABLE.
   */
  public record TaxCredit(String status, String reason, String jurisdiction, String policyVersion, BigDecimal rate,
      Long creditMinor) {}
  /**
   * position: BELOW, WITHIN or ABOVE the range, or null when unavailable. Every amount is an estimate from the source.
   * basis (v2): PROVIDER for ticket 101's adapter, or BUYER_ESTIMATE for a value the Buyer looked up and entered
   * (ticket 106), which carries its {@code checkedOn} date, whether it is {@code old}, and the {@code label} the
   * copy names it by. A v1 result has none of these and is read as PROVIDER.
   */
  public record Valuation(String availability, String reason, String notice, String source, String licence,
      boolean synthetic, Instant retrievedAt, Instant observedAt, String conditionAssumption, Long lowMinor,
      Long typicalMinor, Long highMinor, String position, Long belowLowMinor, Long aboveHighMinor,
      String basis, String estimateId, String checkedOn, Boolean old, String label) {
    boolean buyerEstimate() { return BUYER_ESTIMATE.equals(basis); }
    /** A single looked-up value rather than a range. */
    boolean single() { return buyerEstimate() && Objects.equals(lowMinor, highMinor); }
  }
  static final String PROVIDER = "PROVIDER", BUYER_ESTIMATE = "BUYER_ESTIMATE";
  /** status: SAME, DIFFERENT, NOT_ON_PAPERWORK or NO_TRADE_RECORDED. */
  public record VinMatch(String status, String paperworkVin, String recordedVin) {}
  /**
   * TRADE-05: the allowance above the trade's value (the best written offer, else the top of the valuation
   * range) next to the price increase over the Agreed Price. netEffectMinor is positive when the Buyer is ahead.
   * reference: WRITTEN_OFFER, VALUATION_HIGH, or BUYER_ESTIMATE_HIGH when the Buyer's estimate stood in.
   */
  public record PriceTogether(String reference, Long referenceMinor, Long allowanceAboveValueMinor,
      Long priceIncreaseMinor, Long netEffectMinor) {}
  /** severity: BLOCKING, WARNING or INFORMATIONAL. estimate marks lines that rest on the valuation range. */
  public record Finding(String code, String severity, String summary, Long amountMinor, String deskQuestion, boolean estimate) {}

  /** The case's tax jurisdiction and the dealer ZIP the valuation is asked for. */
  record CaseFacts(String jurisdiction, String zip) {}
  private record Cached(TradeValuationAdapter.Result result, Instant at) {}

  private final TradeService trades;
  private final TradeValuationService valuations;
  private final CashEconomicsPersistence policies;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final FinancingCalculator financing = new FinancingCalculator();
  /** Repeated Desk Check reads reuse one valuation per trade revision briefly; nothing is persisted. */
  private final Map<String, Cached> recent = new ConcurrentHashMap<>();

  @Autowired
  public TradeDeskCheck(TradeService trades, TradeValuationService valuations, CashEconomicsPersistence policies,
      JdbcTemplate jdbc) {
    this(trades, valuations, policies, jdbc, Clock.systemUTC());
  }

  TradeDeskCheck(TradeService trades, TradeValuationService valuations, CashEconomicsPersistence policies,
      JdbcTemplate jdbc, Clock clock) {
    this.trades = trades; this.valuations = valuations; this.policies = policies; this.jdbc = jdbc; this.clock = clock;
  }

  /**
   * @param deskTerms the paperwork's terms; {@code agreedTerms} the Agreed Price's, or null for a fresh case
   */
  public Result check(String caseId, String token, JsonNode deskTerms, JsonNode agreedTerms) {
    Long allowance = allowance(deskTerms);
    if (allowance == null)
      return new Result(VERSION, "NO_ALLOWANCE", "USD", null, null, null, null, null, "UNAVAILABLE", null, null,
          null, null, null, null, null, null, null, List.of());
    var trade = trades.view(caseId, token).trade();
    var facts = facts(caseId);
    Long paperworkPayoff = abs(amount(deskTerms, "TRADE_PAYOFF"));
    String paperworkVin = value(deskTerms, "TRADE_VIN");
    Long sellingPrice = amount(deskTerms, "SELLING_PRICE");
    var tax = taxCredit(facts.jurisdiction(), allowance, sellingPrice);
    List<Finding> findings = new ArrayList<>();
    if (trade == null) {
      findings.add(new Finding("TRADE_NOT_RECORDED", "WARNING", "This paperwork shows a trade-in allowance of "
          + money(allowance) + ", but no trade-in is recorded on this case. Record it to compare the allowance with "
          + "your written offers and the trade's value.", null,
          "What payoff amount and trade-in VIN did you use for this allowance?", false));
      Long equity = paperworkPayoff == null ? null : allowance - paperworkPayoff;
      return new Result(VERSION, "TRADE_NOT_RECORDED", "USD", allowance, paperworkPayoff, null, null, equity,
          equity == null ? "UNAVAILABLE" : "PAPERWORK_PAYOFF", null, null, null, null, tax, null, null,
          new VinMatch("NO_TRADE_RECORDED", paperworkVin, null), null, List.copyOf(findings));
    }

    var vin = vinMatch(paperworkVin, trade.vehicle().vin());
    if (vin.status().equals("DIFFERENT"))
      findings.add(new Finding("TRADE_VIN_DIFFERENT", "BLOCKING", "The trade-in VIN on this paperwork is "
          + paperworkVin + ", but your recorded trade-in is VIN " + trade.vehicle().vin()
          + ". Nothing about this trade is compared until the VIN is the car you are trading in.", null,
          "The trade-in VIN here is not my car's. Will you correct it to " + trade.vehicle().vin() + "?", false));
    else if (vin.status().equals("SAME"))
      findings.add(info("TRADE_VIN_SAME", "The trade-in VIN on this paperwork is the same as your recorded trade-in."));
    else findings.add(info("TRADE_VIN_NOT_SHOWN", "This paperwork does not show the trade-in VIN, so it is not "
        + "compared with your recorded trade-in. Ask to see it on the buyer's order."));
    boolean compare = !vin.status().equals("DIFFERENT");

    // Best unexpired written offer, with the sales tax the Buyer keeps only by trading in.
    BestOffer best = trade.offers().stream().filter(offer -> offer.id().equals(trade.bestOfferId())).findFirst()
        .map(offer -> new BestOffer(offer.id(), offer.amountMinor(), offer.source(), offer.offerDate(), offer.expiresOn(),
            "the written offer from " + offer.source() + " dated " + offer.offerDate())).orElse(null);
    Long shortfall = best == null ? null : Math.max(0, best.amountMinor() - allowance);
    Long gapAfterTax = best == null || !"AVAILABLE".equals(tax.status()) && !"NOT_APPLICABLE".equals(tax.status()) ? null
        : best.amountMinor() - allowance - tax.creditMinor();
    if (compare && best != null) {
      if (shortfall > 0)
        findings.add(new Finding("BELOW_WRITTEN_OFFER", "WARNING", "The trade-in allowance of " + money(allowance) + " is "
            + money(shortfall) + " below " + best.label() + " for " + money(best.amountMinor()) + "." + taxSentence(tax, gapAfterTax),
            shortfall, "I have a written offer of " + money(best.amountMinor()) + " for my trade. Will you match it?", false));
      else findings.add(info("AT_OR_ABOVE_WRITTEN_OFFER", "The trade-in allowance of " + money(allowance)
          + " is at or above " + best.label() + " for " + money(best.amountMinor()) + "."));
    }

    // A licensed valuation range, labelled as an estimate from its source; without one, the Buyer's own
    // estimate, named by where and when they looked it up. Nothing is estimated here.
    var valuation = compare ? valuation(caseId, trade, facts.zip(), allowance) : null;
    if (valuation != null) {
      if (!"AVAILABLE".equals(valuation.availability()))
        findings.add(info("VALUATION_UNAVAILABLE", "A trade-in value range is unavailable: " + valuation.notice()
            + " The allowance is not compared with a value range."));
      else if (valuation.buyerEstimate()) findings.add(estimateFinding(valuation, allowance));
      else if ("BELOW".equals(valuation.position()))
        findings.add(new Finding("BELOW_VALUATION_RANGE", "WARNING", "The trade-in allowance of " + money(allowance)
            + " is " + money(valuation.belowLowMinor()) + " below the low end of the trade-in range, "
            + money(valuation.lowMinor()) + " to " + money(valuation.highMinor()) + " (an estimate from " + valuation.source()
            + ").", valuation.belowLowMinor(),
            "The trade-in range I have starts at " + money(valuation.lowMinor()) + ". How did you arrive at " + money(allowance) + "?", true));
      else findings.add(new Finding("ABOVE".equals(valuation.position()) ? "ABOVE_VALUATION_RANGE" : "WITHIN_VALUATION_RANGE",
          "INFORMATIONAL", "The trade-in allowance of " + money(allowance) + " is "
              + ("ABOVE".equals(valuation.position()) ? "above" : "within") + " the trade-in range, "
              + money(valuation.lowMinor()) + " to " + money(valuation.highMinor()) + " (an estimate from "
              + valuation.source() + ").", null, null, true));
    }

    // Payoff: the paperwork's against the Buyer's; stale or unknown never counts as the same.
    var buyerPayoff = buyerPayoff(trade);
    Long payoffDifference = null;
    if (compare) {
      switch (buyerPayoff.state()) {
        case "NOT_OWED" -> { if (paperworkPayoff != null && paperworkPayoff > 0) {
          payoffDifference = paperworkPayoff;
          findings.add(new Finding("PAYOFF_NOT_OWED", "WARNING", "This paperwork uses a payoff of " + money(paperworkPayoff)
              + ", but you recorded that nothing is owed on your trade-in.", paperworkPayoff,
              "I don't owe anything on my trade. Why is there a payoff of " + money(paperworkPayoff) + "?", false)); } }
        case "CURRENT" -> {
          if (paperworkPayoff == null) findings.add(new Finding("PAYOFF_NOT_SHOWN", "WARNING", "This paperwork does not show "
              + "the payoff on your trade-in. You recorded " + money(buyerPayoff.amountMinor()) + " valid through "
              + buyerPayoff.goodThrough() + ".", null, "Where is the payoff for my trade-in, and what amount did you use?", false));
          else if (!paperworkPayoff.equals(buyerPayoff.amountMinor())) {
            payoffDifference = paperworkPayoff - buyerPayoff.amountMinor();
            findings.add(new Finding("PAYOFF_DIFFERS", "WARNING", "This paperwork uses a payoff of " + money(paperworkPayoff)
                + ", " + money(Math.abs(payoffDifference)) + (payoffDifference > 0 ? " more" : " less")
                + " than your payoff of " + money(buyerPayoff.amountMinor()) + " valid through " + buyerPayoff.goodThrough() + ".",
                Math.abs(payoffDifference), "My lender's payoff is " + money(buyerPayoff.amountMinor()) + " valid through "
                    + buyerPayoff.goodThrough() + ". Why does this use " + money(paperworkPayoff) + "?", false));
          } else findings.add(info("PAYOFF_SAME", "The payoff on this paperwork, " + money(paperworkPayoff)
              + ", is the same as your payoff valid through " + buyerPayoff.goodThrough() + "."));
        }
        case "STALE" -> findings.add(new Finding("PAYOFF_STALE", "WARNING", "Your recorded payoff of "
            + money(buyerPayoff.amountMinor()) + " was valid through " + buyerPayoff.goodThrough() + " and is now stale, so "
            + (paperworkPayoff == null ? "the payoff cannot be checked." : "the " + money(paperworkPayoff)
                + " on this paperwork cannot be checked against it.") + " Get a current payoff quote from your lender.",
            null, "What payoff did you get from my lender, and what date is it valid through?", false));
        default -> findings.add(new Finding("PAYOFF_UNKNOWN", "WARNING", "Your payoff is unknown, so "
            + (paperworkPayoff == null ? "what is owed on your trade-in cannot be checked." : "the payoff of "
                + money(paperworkPayoff) + " on this paperwork cannot be checked.")
            + " Get a payoff quote from your lender.", null,
            "What payoff did you get from my lender, and what date is it valid through?", false));
      }
    }

    // Equity = allowance - payoff, from the paperwork's payoff, else the Buyer's.
    Long payoffUsed; String basis;
    if (paperworkPayoff != null) { payoffUsed = paperworkPayoff; basis = "PAPERWORK_PAYOFF"; }
    else if ("NOT_OWED".equals(buyerPayoff.state())) { payoffUsed = 0L; basis = "NOTHING_OWED"; }
    else if (buyerPayoff.amountMinor() != null) { payoffUsed = buyerPayoff.amountMinor(); basis = "BUYER_PAYOFF"; }
    else { payoffUsed = null; basis = "UNAVAILABLE"; }
    Long equity = compare && payoffUsed != null ? allowance - payoffUsed : null;
    if (!compare) basis = "UNAVAILABLE";
    boolean loan = LOAN.stream().anyMatch(field -> value(deskTerms, field) != null);
    Long negativeFinanced = equity != null && equity < 0 && loan ? -equity : null;
    NegativeEquityCost cost = negativeFinanced == null ? null : negativeEquityCost(deskTerms, negativeFinanced);
    if (equity != null && equity < 0) {
      if (negativeFinanced != null)
        findings.add(new Finding("NEGATIVE_EQUITY_FINANCED", "WARNING", "You owe " + money(-equity) + " more than the "
            + "allowance, and that negative equity is added to the amount financed." + costSentence(cost),
            "AVAILABLE".equals(cost.status()) ? cost.scheduledPaymentsIncreaseMinor() : -equity,
            "How much of the amount financed is the " + money(-equity) + " I still owe on my trade?", false));
      else findings.add(new Finding("NEGATIVE_EQUITY", "WARNING", "You owe " + money(-equity) + " more than the "
          + "allowance. That negative equity raises what you pay for this car by " + money(-equity) + ".", -equity,
          "Where is the " + money(-equity) + " I still owe on my trade shown in the amount due?", false));
    } else if (equity != null)
      findings.add(info("EQUITY", "Trade equity is " + money(equity) + ": the allowance of " + money(allowance)
          + " less a payoff of " + money(payoffUsed) + "."));

    // TRADE-05: an allowance above the trade's value is shown next to any price increase, never netted silently.
    PriceTogether together = null;
    if (compare) {
      String reference = best != null ? "WRITTEN_OFFER" : valuation == null || valuation.highMinor() == null ? null
          : valuation.buyerEstimate() ? "BUYER_ESTIMATE_HIGH" : "VALUATION_HIGH";
      Long referenceMinor = best != null ? Long.valueOf(best.amountMinor()) : reference == null ? null : valuation.highMinor();
      Long above = referenceMinor == null || allowance <= referenceMinor ? null : allowance - referenceMinor;
      Long increase = priceIncrease(deskTerms, agreedTerms);
      if (above != null || increase != null)
        together = new PriceTogether(reference, referenceMinor, above, increase,
            above == null || increase == null ? null : above - increase);
      String against = "WRITTEN_OFFER".equals(reference) ? best.label() + " for " + money(referenceMinor)
          : "BUYER_ESTIMATE_HIGH".equals(reference) ? (valuation.single() ? "" : "the top of ") + valuation.label()
              + " (" + money(referenceMinor) + ")"
          : "the top of the trade-in range (" + money(referenceMinor == null ? 0 : referenceMinor) + ", an estimate from "
              + (valuation == null ? "" : valuation.source()) + ")";
      boolean estimated = "VALUATION_HIGH".equals(reference) || "BUYER_ESTIMATE_HIGH".equals(reference);
      if (above != null && increase != null && increase > 0) {
        long net = above - increase;
        findings.add(new Finding("ALLOWANCE_OFFSET_BY_PRICE", "WARNING", "The trade-in allowance is " + money(above)
            + " above " + against + ", but the price is " + money(increase) + " higher than agreed, so together they leave you "
            + (net == 0 ? "even." : money(Math.abs(net)) + (net > 0 ? " ahead." : " behind.")), net < 0 ? -net : null,
            "If you lower the price back to what we agreed, what is the trade-in allowance?", estimated));
      } else if (above != null)
        findings.add(new Finding("ALLOWANCE_ABOVE_VALUE", "INFORMATIONAL", "The trade-in allowance is " + money(above)
            + " above " + against + ". Judge it together with the selling price, which is where a higher allowance can be "
            + "paid for.", null, "What is the selling price without the trade-in?", estimated));
    }

    findings.sort(Comparator.comparingInt(finding -> ORDER.indexOf(finding.code())));
    return new Result(VERSION, "CHECKED", "USD", allowance, paperworkPayoff, buyerPayoff, payoffDifference, equity, basis,
        negativeFinanced, cost, best, compare ? shortfall : null, tax, compare ? gapAfterTax : null, valuation, vin, together,
        List.copyOf(findings));
  }

  /** The allowance against the Buyer's own estimate, named by its source and date; an old estimate is said to be old. */
  private static Finding estimateFinding(Valuation estimate, long allowance) {
    String amount = estimate.single() ? money(estimate.lowMinor())
        : money(estimate.lowMinor()) + " to " + money(estimate.highMinor());
    String old = Boolean.TRUE.equals(estimate.old()) ? " That estimate is old: you looked it up on " + estimate.checkedOn()
        + ". Look it up again before relying on it." : "";
    if ("BELOW".equals(estimate.position()))
      return new Finding("BELOW_VALUATION_RANGE", "WARNING", "The trade-in allowance of " + money(allowance) + " is "
          + money(estimate.belowLowMinor()) + " below " + (estimate.single() ? "" : "the low end of ") + estimate.label()
          + ", " + amount + "." + old, estimate.belowLowMinor(),
          "My " + estimate.source() + " estimate for this car is " + amount + ". How did you arrive at " + money(allowance) + "?", true);
    boolean above = "ABOVE".equals(estimate.position());
    return new Finding(above ? "ABOVE_VALUATION_RANGE" : "WITHIN_VALUATION_RANGE", "INFORMATIONAL", "The trade-in allowance of "
        + money(allowance) + " is " + (above ? "above " : "within ") + estimate.label() + ", " + amount + "." + old,
        null, null, true);
  }

  private static Finding info(String code, String summary) {
    return new Finding(code, "INFORMATIONAL", summary, null, null, false);
  }

  private static String taxSentence(TaxCredit tax, Long gapAfterTax) {
    if ("UNAVAILABLE".equals(tax.status())) return " " + tax.reason();
    if (!"AVAILABLE".equals(tax.status()) || tax.creditMinor() == 0 || gapAfterTax == null) return "";
    return " Trading in lowers the sales tax on this car by about " + money(tax.creditMinor()) + " under "
        + tax.jurisdiction() + " tax rules, which selling it elsewhere would not, so after tax the offer is "
        + (gapAfterTax > 0 ? "about " + money(gapAfterTax) + " ahead of this allowance."
            : gapAfterTax == 0 ? "even with this allowance." : "about " + money(-gapAfterTax) + " behind this allowance.");
  }

  private static String costSentence(NegativeEquityCost cost) {
    if (!"AVAILABLE".equals(cost.status())) return " " + cost.reason();
    return " At " + cost.aprPercent().stripTrailingZeros().toPlainString() + "% APR for " + cost.termMonths()
        + " months it adds " + money(cost.paymentIncreaseMinor()) + " a month, " + money(cost.scheduledPaymentsIncreaseMinor())
        + " in scheduled payments, of which " + money(cost.interestMinor()) + " is interest.";
  }

  /** The allowance as a positive amount: the TRADE_ALLOWANCE term, else the sum of trade lines. */
  static Long allowance(JsonNode terms) {
    Long candidate = abs(amount(terms, "TRADE_ALLOWANCE"));
    if (candidate != null) return candidate;
    long sum = 0; boolean found = false;
    for (JsonNode line : terms.path("lineItems"))
      if ("TRADE".equals(line.path("category").asText()) && line.path("amountMinor").isIntegralNumber()) {
        sum = Math.addExact(sum, Math.abs(line.path("amountMinor").longValue())); found = true;
      }
    return found ? sum : null;
  }

  private static VinMatch vinMatch(String paperwork, String recorded) {
    if (paperwork == null) return new VinMatch("NOT_ON_PAPERWORK", null, recorded);
    return new VinMatch(paperwork.equalsIgnoreCase(recorded) ? "SAME" : "DIFFERENT", paperwork, recorded);
  }

  private static BuyerPayoff buyerPayoff(TradeService.Trade trade) {
    if (trade.moneyOwed() == TradeService.MoneyOwed.NO) return new BuyerPayoff("NOT_OWED", 0L, null);
    if (trade.moneyOwed() != TradeService.MoneyOwed.YES || trade.payoffUnknown() || trade.payoffMinor() == null)
      return new BuyerPayoff("UNKNOWN", null, null);
    return new BuyerPayoff(trade.payoffStale() ? "STALE" : "CURRENT", trade.payoffMinor(), trade.payoffGoodThrough());
  }

  /** The negative equity's effect on the loan, through the financing engine like a financed add-on. */
  private NegativeEquityCost negativeEquityCost(JsonNode terms, long negative) {
    Long financed = amount(terms, "AMOUNT_FINANCED");
    BigDecimal apr = decimal(value(terms, "APR"));
    Long term = amount(terms, "TERM_MONTHS");
    if (financed == null || apr == null || term == null || term < 1 || term > 1200)
      return new NegativeEquityCost("UNAVAILABLE", "Its cost over the loan needs the amount financed, APR and term from "
          + "the paperwork, and one of them is missing.", financed, apr, term == null ? null : term.intValue(),
          null, null, null, FinancingCalculator.VERSION);
    var result = financing.calculate(new FinanceLoan("USD", financed, financed, financed, financed, apr, term.intValue(),
        "STANDARD_FIXED_RATE", FinancingCalculator.TIMING, 0L, 0L, 0L, 0L, null,
        List.of(new FinanceLoan.AddOn("Negative equity", negative))));
    if (!"AVAILABLE".equals(result.status()) || result.financedAddOns().isEmpty())
      return new NegativeEquityCost("UNAVAILABLE", "Its cost over the loan could not be calculated from the paperwork's "
          + "amount financed, APR and term.", financed, apr, term.intValue(), null, null, null, FinancingCalculator.VERSION);
    var effect = result.financedAddOns().getFirst();
    return new NegativeEquityCost("AVAILABLE", null, financed, apr, term.intValue(), effect.incrementalPaymentMinor(),
        effect.incrementalScheduledPaymentsMinor(), effect.incrementalInterestMinor(), FinancingCalculator.VERSION);
  }

  /** The trade-in sales-tax credit under the case's jurisdiction policy pack; unavailable without one. */
  private TaxCredit taxCredit(String jurisdiction, long allowance, Long sellingPrice) {
    if (jurisdiction == null || !policies.hasPolicy(jurisdiction))
      return new TaxCredit("UNAVAILABLE", "The sales tax a trade-in removes is not calculated because no tax rules are "
          + "configured for " + (jurisdiction == null ? "your state" : jurisdiction) + " yet.", jurisdiction, null, null, null);
    var policy = policies.requirePolicy(jurisdiction);
    if (!policy.tradeTaxCreditEnabled() || !policy.taxableLineTypes().contains(LineType.SELLING_PRICE))
      return new TaxCredit("NOT_APPLICABLE", null, jurisdiction, policy.version(), policy.taxRate(), 0L);
    if (sellingPrice == null)
      return new TaxCredit("UNAVAILABLE", "The sales tax a trade-in removes is not calculated because the paperwork's "
          + "selling price is missing.", jurisdiction, policy.version(), policy.taxRate(), null);
    // The credit is capped at the taxable basis; the selling price is its lower bound on desk paperwork.
    BigDecimal basis = BigDecimal.valueOf(Math.max(0, sellingPrice), 2);
    BigDecimal withTrade = basis.subtract(BigDecimal.valueOf(allowance, 2)).max(BigDecimal.ZERO);
    long credit = com.dealanalyzer.economics.CashDealEconomicsCalculator.taxAt(basis, policy)
        .setScale(policy.currencyScale(), policy.roundingMode())
        .subtract(com.dealanalyzer.economics.CashDealEconomicsCalculator.taxAt(withTrade, policy)
            .setScale(policy.currencyScale(), policy.roundingMode()))
        .movePointRight(2).longValueExact();
    String explanation = jurisdiction.startsWith("US-FL:")
        ? "Florida state tax plus " + jurisdiction.substring(6)
            + " County surtax on the first $5,000 of the taxable price after the trade-in."
        : null;
    return new TaxCredit("AVAILABLE", explanation, jurisdiction, policy.version(), policy.taxRate(), credit);
  }

  /**
   * The licensed range when the adapter gives one; otherwise the Buyer's most recent estimate, when they
   * recorded one. A synthetic development range is not licensed, so a Buyer estimate stands in for it too.
   */
  private Valuation valuation(String caseId, TradeService.Trade trade, String zip, long allowance) {
    var estimate = trade.estimates() == null ? null : trade.estimates().stream()
        .filter(item -> item.id().equals(trade.estimateInUseId())).findFirst().orElse(null);
    var provided = providerValuation(caseId, trade, zip, allowance);
    boolean licensed = "AVAILABLE".equals(provided.availability()) && !provided.synthetic();
    return estimate == null || licensed ? provided : buyerEstimate(estimate, allowance);
  }

  private Valuation buyerEstimate(TradeService.Estimate estimate, long allowance) {
    long low = estimate.lowMinor(), high = estimate.highMinor() == null ? low : estimate.highMinor();
    String position = allowance < low ? "BELOW" : allowance > high ? "ABOVE" : "WITHIN";
    boolean old = TradeService.estimateOld(java.time.LocalDate.parse(estimate.checkedOn()),
        java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneId.of("America/New_York")));
    String label = "your " + estimate.source() + " estimate from " + estimate.checkedOn();
    String notice = "You looked this value up on " + estimate.source() + " and entered it yourself; otdog has not "
        + "checked it, and it is not an offer." + (old ? " It is old, from " + estimate.checkedOn() + "." : "");
    return new Valuation("AVAILABLE", null, notice, estimate.source(), "BUYER_ENTERED", false, null, null, null, low,
        estimate.highMinor() == null ? low : null, high, position, position.equals("BELOW") ? low - allowance : null,
        position.equals("ABOVE") ? allowance - high : null, BUYER_ESTIMATE, estimate.id(), estimate.checkedOn(), old, label);
  }

  private Valuation providerValuation(String caseId, TradeService.Trade trade, String zip, long allowance) {
    TradeValuationAdapter.Result result;
    if (zip == null) return new Valuation("UNAVAILABLE", "MISSING_FIELDS", "A trade-in value range needs the dealer's "
        + "five-digit ZIP, which this case does not have.", null, null, false, null, null, null, null, null, null, null, null, null,
        PROVIDER, null, null, null, null);
    String key = caseId + "|" + trade.id() + "|" + trade.revision() + "|" + zip;
    var cached = recent.get(key);
    Instant now = clock.instant();
    if (cached != null && cached.at().plus(VALUATION_REUSE).isAfter(now)) result = cached.result();
    else {
      result = valuations.value(caseId, TradeVehicle.from(trade), zip);
      if (result.availability() == TradeValuationAdapter.Availability.AVAILABLE) {
        if (recent.size() >= VALUATION_CACHE_LIMIT) recent.clear();
        recent.put(key, new Cached(result, now));
      }
    }
    var provider = result.provider();
    if (result.availability() != TradeValuationAdapter.Availability.AVAILABLE)
      return new Valuation(result.availability().name(), result.reason().name(), result.notice(), provider.name(),
          provider.licenceMetadata(), provider.synthetic(), result.retrievedAt(), null, null, null, null, null, null, null, null,
          PROVIDER, null, null, null, null);
    var range = result.range();
    String position = allowance < range.lowMinor() ? "BELOW" : allowance > range.highMinor() ? "ABOVE" : "WITHIN";
    return new Valuation("AVAILABLE", null, result.notice(), provider.name(), provider.licenceMetadata(), provider.synthetic(),
        result.retrievedAt(), result.observedAt(), result.conditionAssumption().name(), range.lowMinor(), range.typicalMinor(),
        range.highMinor(), position, position.equals("BELOW") ? range.lowMinor() - allowance : null,
        position.equals("ABOVE") ? allowance - range.highMinor() : null, PROVIDER, null, null, null, null);
  }

  /** Price added since the Agreed Price: a higher selling price plus a smaller discount. Null without one. */
  private static Long priceIncrease(JsonNode desk, JsonNode agreed) {
    if (agreed == null) return null;
    Long was = amount(agreed, "SELLING_PRICE"), now = amount(desk, "SELLING_PRICE");
    if (was == null || now == null) return null;
    long increase = now - was;
    Long agreedDiscount = abs(amount(agreed, "DISCOUNT_TOTAL")), deskDiscount = abs(amount(desk, "DISCOUNT_TOTAL"));
    if (agreedDiscount != null) increase += agreedDiscount - (deskDiscount == null ? 0 : deskDiscount);
    return increase;
  }

  CaseFacts facts(String caseId) {
    return jdbc.query("select buyer_jurisdiction, dealer_location from analysis_cases where id = ?", (r, n) -> {
      String location = r.getString(2);
      String zip = location == null ? null : location.strip();
      return new CaseFacts(r.getString(1), zip != null && zip.matches("[0-9]{5}") ? zip : null);
    }, caseId).stream().findFirst().orElse(new CaseFacts(null, null));
  }

  static String value(JsonNode terms, String field) {
    if (terms == null) return null;
    for (JsonNode term : terms.path("candidates"))
      if (term.path("field").asText().equals(field) && term.path("value").isTextual() && !term.path("value").asText().isBlank())
        return term.path("value").asText();
    return null;
  }
  private static Long amount(JsonNode terms, String field) {
    String text = value(terms, field);
    try { return text == null ? null : Long.valueOf(text); } catch (NumberFormatException unreadable) { return null; }
  }
  private static BigDecimal decimal(String text) {
    try { return text == null ? null : new BigDecimal(text); } catch (NumberFormatException unreadable) { return null; }
  }
  private static Long abs(Long value) { return value == null ? null : Math.abs(value); }
  static String money(long minor) {
    var format = NumberFormat.getCurrencyInstance(Locale.US);
    if (minor % 100 == 0) format.setMaximumFractionDigits(0);
    return format.format(minor / 100.0);
  }
}
