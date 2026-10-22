package com.dealanalyzer.trade.desk;

import com.dealanalyzer.economics.CashDealEconomics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.trade.TradeService;
import com.dealanalyzer.trade.TradeService.*;
import com.dealanalyzer.trade.valuation.DeterministicFakeTradeValuationAdapter;
import com.dealanalyzer.trade.valuation.DeterministicFakeTradeValuationAdapter.Scenario;
import com.dealanalyzer.trade.valuation.TradeValuationService;
import com.dealanalyzer.trade.valuation.TradeVehicle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Ticket 103 rules over labelled synthetic trades, offers and paperwork; no database or provider. */
class TradeDeskCheckTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TRADE_VIN = "5J6RW2H52ML000002";
  private static final String OTHER_VIN = "2T3P1RFV5MW000001";
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T15:00:00Z"), ZoneOffset.UTC);
  private static final String TODAY = "2026-10-10";

  private final TradeService trades = mock(TradeService.class);
  private final TradeValuationService valuations = mock(TradeValuationService.class);
  private final CashEconomicsPersistence policies = mock(CashEconomicsPersistence.class);
  private String jurisdiction = "US-NC";

  @Test
  void valuationUsesBuyerZipForDealerCityAndKeepsOlderDealerZips() {
    var data = new org.springframework.jdbc.datasource.DriverManagerDataSource(
        "jdbc:h2:mem:trade-location-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(data);
    jdbc.execute("create table analysis_cases(id varchar, buyer_jurisdiction varchar, dealer_location varchar, buyer_zip varchar)");
    jdbc.update("insert into analysis_cases values ('city', 'US-NC', 'Raleigh, NC', '27601'), ('zip', 'US-NC', '27604', '27601')");
    recorded(MoneyOwed.NO, null, null, false);
    when(valuations.value(anyString(), any(TradeVehicle.class), anyString()))
        .thenAnswer(call -> new DeterministicFakeTradeValuationAdapter(CLOCK, Scenario.NORMAL).value(call.getArgument(1), call.getArgument(2)));
    var check = new TradeDeskCheck(trades, valuations, policies, jdbc, CLOCK);
    check.check("city", "token", terms("TRADE_ALLOWANCE", "1500000"), null);
    check.check("zip", "token", terms("TRADE_ALLOWANCE", "1500000"), null);
    verify(valuations).value(eq("city"), any(TradeVehicle.class), eq("27601"));
    verify(valuations).value(eq("zip"), any(TradeVehicle.class), eq("27604"));
  }

  private TradeDeskCheck check(Scenario valuation) {
    var adapter = new DeterministicFakeTradeValuationAdapter(CLOCK, valuation);
    when(valuations.value(anyString(), any(TradeVehicle.class), anyString()))
        .thenAnswer(call -> adapter.value(call.getArgument(1), call.getArgument(2)));
    when(policies.hasPolicy("US-NC")).thenReturn(true);
    when(policies.requirePolicy("US-NC")).thenReturn(policy("US-NC.cash.v1", "US-NC", "0.03"));
    return new TradeDeskCheck(trades, valuations, policies, null, CLOCK) {
      @Override CaseFacts facts(String caseId) { return new CaseFacts(jurisdiction, "27604"); }
    };
  }

  private void recorded(MoneyOwed owed, Long payoff, String goodThrough, boolean stale, Offer... offers) {
    recorded(List.of(), owed, payoff, goodThrough, stale, offers);
  }

  private void recorded(List<Estimate> estimates, MoneyOwed owed, Long payoff, String goodThrough, boolean stale, Offer... offers) {
    var best = Arrays.stream(offers).filter(Offer::usedForComparison).max(Comparator.comparingLong(Offer::amountMinor))
        .map(Offer::id).orElse(null);
    var trade = new Trade("trade-1", 3, new Vehicle(TRADE_VIN, null, "SYNTHETIC", "v1", Instant.EPOCH, "2021", "Honda",
        "CR-V", "EX"), 61000, Condition.GOOD, owed, owed == MoneyOwed.YES ? "Synthetic Credit Union" : null, payoff,
        goodThrough, owed != MoneyOwed.NO && payoff == null, stale, true, Instant.EPOCH, List.of(offers), best, estimates,
        estimates.isEmpty() ? null : estimates.getLast().id());
    when(trades.view(anyString(), anyString())).thenReturn(new View("CASE", trade, null, List.of()));
  }

  private static Offer offer(String id, long amount, String source, boolean expired) {
    return new Offer(id, amount, source, "2026-10-08", expired ? "2026-10-09" : "2026-10-15", expired, !expired, null, Instant.EPOCH);
  }

  private static Estimate estimate(String id, String source, long low, Long high, String checkedOn) {
    return new Estimate(id, source, low, high, checkedOn, false, Instant.EPOCH);
  }

  private static JsonNode terms(Object... fieldsAndValues) {
    ObjectNode terms = JSON.createObjectNode();
    var candidates = terms.putArray("candidates");
    terms.putArray("lineItems");
    for (int i = 0; i < fieldsAndValues.length; i += 2)
      candidates.addObject().put("field", (String) fieldsAndValues[i]).put("value", fieldsAndValues[i + 1].toString());
    return terms;
  }

  private static List<String> codes(TradeDeskCheck.Result result) {
    return result.findings().stream().map(TradeDeskCheck.Finding::code).toList();
  }

  @Test void aDealerInAnotherStateGetsNoTradeTaxCreditButTheSameOfferComparison() {
    // Ticket 141: NC registration, Texas dealer. The allowance comparison stands; the tax part is not calculated.
    var adapter = new DeterministicFakeTradeValuationAdapter(CLOCK, Scenario.WIDE_RANGE);
    when(valuations.value(anyString(), any(TradeVehicle.class), anyString()))
        .thenAnswer(call -> adapter.value(call.getArgument(1), call.getArgument(2)));
    when(policies.hasPolicy("US-NC")).thenReturn(true);
    when(policies.requirePolicy("US-NC")).thenReturn(policy("US-NC.cash.v1", "US-NC", "0.03"));
    var desk = new TradeDeskCheck(trades, valuations, policies, null, CLOCK) {
      @Override CaseFacts facts(String caseId) { return new CaseFacts("US-NC", "75201", "US-TX"); }
    };
    recorded(MoneyOwed.NO, null, null, false, offer("best", 1_400_000, "Synthetic Car Exchange", false));
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_VIN", TRADE_VIN,
        "SELLING_PRICE", 2_500_000), null);

    assertThat(result.taxCredit().status()).isEqualTo("NOT_CHECKED");
    assertThat(result.taxCredit().creditMinor()).isNull();
    assertThat(result.offerGapAfterTaxMinor()).isNull();
    assertThat(result.shortfallToOfferMinor()).isEqualTo(250_000);
    assertThat(result.leading().getFirst().summary()).contains("$2,500 below", "tax is not checked")
        .doesNotContain("about $345");
  }

  @Test void syntheticFloridaOutsideOfferComparisonUsesTheDifferenceInCappedTaxes() {
    var desk = check(Scenario.UNAVAILABLE);
    jurisdiction = "US-FL:Broward";
    when(policies.hasPolicy(jurisdiction)).thenReturn(true);
    var flat = policy("synthetic-florida-components", jurisdiction, ".06");
    var componentPolicy = new Policy(flat.version(), flat.jurisdiction(), flat.currency(), flat.taxRate(),
        flat.taxableLineTypes(), true, 2, RoundingMode.HALF_UP, BigDecimal.ONE, List.of(
        new com.dealanalyzer.economics.CashDealEconomics.TaxComponent("Synthetic state", new BigDecimal(".06"),
            com.dealanalyzer.economics.CashDealEconomics.TaxBase.TAXABLE_PRICE_AFTER_TRADE, null, "synthetic", TODAY),
        new com.dealanalyzer.economics.CashDealEconomics.TaxComponent("Synthetic county", new BigDecimal(".01"),
            com.dealanalyzer.economics.CashDealEconomics.TaxBase.TAXABLE_PRICE_AFTER_TRADE, new BigDecimal("5000"), "synthetic", TODAY)));
    when(policies.requirePolicy(jurisdiction)).thenReturn(componentPolicy);
    recorded(MoneyOwed.NO, null, null, false, offer("synthetic-written", 500_000, "Synthetic outside buyer", false));
    var belowCap = desk.check("case", "token", terms("TRADE_ALLOWANCE", 400_000, "SELLING_PRICE", 800_000), null);
    assertThat(belowCap.taxCredit().creditMinor()).isEqualTo(25_000); // $530 without trade minus $280 with trade
    assertThat(belowCap.offerGapAfterTaxMinor()).isEqualTo(75_000);
    assertThat(belowCap.taxCredit().reason()).contains("Broward County", "$5,000");
    var aboveCap = desk.check("case", "token", terms("TRADE_ALLOWANCE", 400_000, "SELLING_PRICE", 3_500_000), null);
    assertThat(aboveCap.taxCredit().creditMinor()).isEqualTo(24_000); // both county bases capped: no county credit
    assertThat(aboveCap.offerGapAfterTaxMinor()).isEqualTo(76_000);
  }

  @Test void scenario28AnAllowanceBelowTheBestUnexpiredOfferLeadsWithTheShortfallAndNamesTheOffer() {
    var desk = check(Scenario.WIDE_RANGE);
    recorded(MoneyOwed.NO, null, null, false, offer("expired", 1_500_000, "Synthetic Expired Buyer", true),
        offer("best", 1_400_000, "Synthetic Car Exchange", false), offer("lower", 1_200_000, "Synthetic Lot", false));
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_VIN", TRADE_VIN,
        "SELLING_PRICE", 2_500_000), null);

    assertThat(result.status()).isEqualTo("CHECKED");
    var lead = result.leading().getFirst();
    assertThat(lead.code()).isEqualTo("BELOW_WRITTEN_OFFER");
    assertThat(lead.amountMinor()).isEqualTo(250_000);
    assertThat(lead.summary()).contains("$2,500 below", "Synthetic Car Exchange", "$14,000").doesNotContain("Expired");
    assertThat(result.bestOffer().id()).as("an expired offer is never compared").isEqualTo("best");
    assertThat(result.shortfallToOfferMinor()).isEqualTo(250_000);
    // NC policy: 3% on the $11,500 allowance stays with the Buyer only when trading in.
    assertThat(result.taxCredit().status()).isEqualTo("AVAILABLE");
    assertThat(result.taxCredit().creditMinor()).isEqualTo(34_500);
    assertThat(result.offerGapAfterTaxMinor()).isEqualTo(250_000 - 34_500);
    assertThat(lead.summary()).contains("about $345", "US-NC", "about $2,155 ahead");
    assertThat(result.vinMatch().status()).isEqualTo("SAME");
    assertThat(result.equityMinor()).as("nothing owed").isEqualTo(1_150_000);
    assertThat(result.equityBasis()).isEqualTo("NOTHING_OWED");
  }

  @Test void scenario29NegativeEquityAddedToTheLoanIsPricedThroughTheFinancingEngineLikeAnAddOn() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.YES, 1_800_000L, TODAY, false);
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000, "TRADE_PAYOFF", 1_800_000,
        "AMOUNT_FINANCED", 3_000_000, "APR", "6.9", "TERM_MONTHS", 72), null);

    assertThat(result.equityMinor()).isEqualTo(-300_000);
    assertThat(result.negativeEquityFinancedMinor()).isEqualTo(300_000);
    var expected = new FinancingCalculator().calculate(new FinanceLoan("USD", 3_000_000L, 3_000_000L, 3_000_000L, 3_000_000L,
        new BigDecimal("6.9"), 72, "STANDARD_FIXED_RATE", FinancingCalculator.TIMING, 0L, 0L, 0L, 0L, null,
        List.of(new FinanceLoan.AddOn("Negative equity", 300_000L)))).financedAddOns().getFirst();
    var cost = result.negativeEquityCost();
    assertThat(cost.status()).isEqualTo("AVAILABLE");
    assertThat(cost.paymentIncreaseMinor()).isEqualTo(expected.incrementalPaymentMinor()).isPositive();
    assertThat(cost.scheduledPaymentsIncreaseMinor()).isEqualTo(expected.incrementalScheduledPaymentsMinor());
    assertThat(cost.interestMinor()).isEqualTo(expected.incrementalInterestMinor()).isPositive();
    var finding = result.findings().stream().filter(item -> item.code().equals("NEGATIVE_EQUITY_FINANCED")).findFirst().orElseThrow();
    assertThat(finding.summary()).contains("$3,000", "amount financed", "72 months", "interest");
    assertThat(codes(result)).contains("PAYOFF_SAME");

    // Without a loan on the paperwork, negative equity raises the amount due instead.
    var cash = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000, "TRADE_PAYOFF", 1_800_000), null);
    assertThat(cash.negativeEquityFinancedMinor()).isNull();
    assertThat(codes(cash)).contains("NEGATIVE_EQUITY").doesNotContain("NEGATIVE_EQUITY_FINANCED");
  }

  @Test void scenario30APayoffThatDiffersFromTheBuyersIsFlaggedAndStaleOrUnknownIsNeverTheSame() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.YES, 1_800_000L, TODAY, false);
    var differs = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000, "TRADE_PAYOFF", 1_920_000), null);
    assertThat(differs.payoffDifferenceMinor()).isEqualTo(120_000);
    var finding = differs.leading().stream().filter(item -> item.code().equals("PAYOFF_DIFFERS")).findFirst().orElseThrow();
    assertThat(finding.amountMinor()).isEqualTo(120_000);
    assertThat(finding.summary()).contains("$19,200", "$1,200 more", "$18,000", TODAY);

    // Stale: even an equal amount is not treated as the same.
    recorded(MoneyOwed.YES, 1_800_000L, "2026-10-01", true);
    var stale = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000, "TRADE_PAYOFF", 1_800_000), null);
    assertThat(codes(stale)).contains("PAYOFF_STALE").doesNotContain("PAYOFF_SAME");
    assertThat(stale.buyerPayoff().state()).isEqualTo("STALE");

    recorded(MoneyOwed.NOT_SURE, null, null, false);
    var unknown = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000, "TRADE_PAYOFF", 1_800_000), null);
    assertThat(codes(unknown)).contains("PAYOFF_UNKNOWN").doesNotContain("PAYOFF_SAME");
    assertThat(unknown.payoffDifferenceMinor()).isNull();
    assertThat(unknown.equityBasis()).as("the paperwork's payoff still gives equity").isEqualTo("PAPERWORK_PAYOFF");
    recorded(MoneyOwed.NOT_SURE, null, null, false);
    var noPayoffAnywhere = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000), null);
    assertThat(noPayoffAnywhere.equityMinor()).as("unknown is never zero").isNull();

    recorded(MoneyOwed.NO, null, null, false);
    var notOwed = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000, "TRADE_PAYOFF", 500_000), null);
    assertThat(codes(notOwed)).contains("PAYOFF_NOT_OWED");
  }

  @Test void aDifferentTradeVinBlocksAndNothingElseIsCompared() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.YES, 1_800_000L, TODAY, false, offer("best", 1_400_000, "Synthetic Car Exchange", false));
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_PAYOFF", 1_920_000,
        "TRADE_VIN", OTHER_VIN), null);
    assertThat(result.blocking()).isTrue();
    assertThat(result.findings().getFirst().code()).isEqualTo("TRADE_VIN_DIFFERENT");
    assertThat(result.findings().getFirst().severity()).isEqualTo("BLOCKING");
    assertThat(codes(result)).containsExactly("TRADE_VIN_DIFFERENT");
    assertThat(result.equityMinor()).isNull();
    assertThat(result.valuation()).isNull();
    verifyNoInteractions(valuations);
  }

  @Test void theValuationRangeIsLabelledAsAnEstimateFromItsSourceAndUnavailabilityIsNeverEstimated() {
    var desk = check(Scenario.NORMAL); // $14,000 to $18,000
    recorded(MoneyOwed.NO, null, null, false);
    var below = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    var finding = below.leading().stream().filter(item -> item.code().equals("BELOW_VALUATION_RANGE")).findFirst().orElseThrow();
    assertThat(finding.amountMinor()).isEqualTo(250_000);
    assertThat(finding.estimate()).isTrue();
    assertThat(finding.summary()).contains("$2,500 below", "$14,000 to $18,000", "estimate from SYNTHETIC_TRADE_VALUATION");
    assertThat(below.valuation().synthetic()).isTrue();

    var within = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000), null);
    var plain = within.findings().stream().filter(item -> item.code().equals("WITHIN_VALUATION_RANGE")).findFirst().orElseThrow();
    assertThat(plain.severity()).isEqualTo("INFORMATIONAL");
    assertThat(plain.summary()).contains("within the trade-in range", "estimate from SYNTHETIC_TRADE_VALUATION");
    var above = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_900_000), null);
    assertThat(codes(above)).contains("ABOVE_VALUATION_RANGE", "ALLOWANCE_ABOVE_VALUE");

    var unavailable = check(Scenario.UNAVAILABLE);
    var none = unavailable.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    assertThat(none.valuation().availability()).isEqualTo("UNAVAILABLE");
    assertThat(none.valuation().lowMinor()).isNull();
    assertThat(none.valuation().position()).isNull();
    assertThat(codes(none)).contains("VALUATION_UNAVAILABLE").doesNotContain("BELOW_VALUATION_RANGE");
  }

  @Test void withoutALicensedRangeTheBuyersEstimateStandsInNamedByWhereAndWhenItWasLookedUp() {
    var desk = check(Scenario.UNAVAILABLE);
    recorded(List.of(estimate("older", "Kelley Blue Book", 1_000_000, 1_200_000L, "2026-09-20"),
        estimate("carfax", "Carfax", 1_400_000, 1_600_000L, "2026-10-05")), MoneyOwed.NO, null, null, false);
    var below = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    var valuation = below.valuation();
    assertThat(valuation.basis()).isEqualTo("BUYER_ESTIMATE");
    assertThat(valuation.estimateId()).as("the most recently looked-up estimate is used").isEqualTo("carfax");
    assertThat(valuation.label()).isEqualTo("your Carfax estimate from 2026-10-05");
    assertThat(valuation.licence()).isEqualTo("BUYER_ENTERED");
    assertThat(valuation.old()).isFalse();
    assertThat(valuation.notice()).contains("entered it yourself", "not an offer");
    assertThat(codes(below)).contains("BELOW_VALUATION_RANGE").doesNotContain("VALUATION_UNAVAILABLE");
    var finding = below.leading().stream().filter(item -> item.code().equals("BELOW_VALUATION_RANGE")).findFirst().orElseThrow();
    assertThat(finding.amountMinor()).isEqualTo(250_000);
    assertThat(finding.estimate()).isTrue();
    assertThat(finding.summary()).contains("$2,500 below the low end of your Carfax estimate from 2026-10-05, $14,000 to $16,000");
    assertThat(finding.deskQuestion()).contains("My Carfax estimate", "$14,000 to $16,000");
    assertThat(below.bestOffer()).as("an estimate is never an offer").isNull();
    assertThat(below.shortfallToOfferMinor()).isNull();

    // A single value: at it is within, above it is said plainly, and an allowance above it sits next to the price.
    recorded(List.of(estimate("one", "Carfax", 1_500_000, null, "2026-10-05")), MoneyOwed.NO, null, null, false);
    var at = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000), null);
    assertThat(at.valuation().typicalMinor()).isEqualTo(1_500_000);
    assertThat(at.findings().stream().filter(item -> item.code().equals("WITHIN_VALUATION_RANGE")).findFirst().orElseThrow()
        .summary()).contains("within your Carfax estimate from 2026-10-05, $15,000.");
    var agreed = terms("SELLING_PRICE", 2_500_000);
    var paidFor = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_700_000, "SELLING_PRICE", 2_800_000), agreed);
    assertThat(codes(paidFor)).contains("ABOVE_VALUATION_RANGE", "ALLOWANCE_OFFSET_BY_PRICE");
    assertThat(paidFor.priceTogether().reference()).isEqualTo("BUYER_ESTIMATE_HIGH");
    assertThat(paidFor.priceTogether().netEffectMinor()).isEqualTo(200_000 - 300_000);
    var offset = paidFor.leading().stream().filter(item -> item.code().equals("ALLOWANCE_OFFSET_BY_PRICE")).findFirst().orElseThrow();
    assertThat(offset.summary()).contains("$2,000 above your Carfax estimate from 2026-10-05 ($15,000)", "$1,000 behind");
    assertThat(offset.estimate()).isTrue();
  }

  @Test void anEstimateOlderThanThirtyDaysIsCalledOldNotIgnored() {
    var desk = check(Scenario.UNAVAILABLE);
    recorded(List.of(estimate("carfax", "Carfax", 1_400_000, 1_600_000L, "2026-09-09")), MoneyOwed.NO, null, null, false);
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    assertThat(result.valuation().old()).isTrue();
    var finding = result.leading().stream().filter(item -> item.code().equals("BELOW_VALUATION_RANGE")).findFirst().orElseThrow();
    assertThat(finding.severity()).isEqualTo("WARNING");
    assertThat(finding.summary()).contains("That estimate is old", "2026-09-09");
    // Thirty days ago exactly is not yet old.
    recorded(List.of(estimate("carfax", "Carfax", 1_400_000, 1_600_000L, "2026-09-10")), MoneyOwed.NO, null, null, false);
    assertThat(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null).valuation().old()).isFalse();
  }

  @Test void aLicensedRangeTakesPrecedenceOverTheBuyersEstimate() {
    var estimates = List.of(estimate("carfax", "Carfax", 1_000_000, null, "2026-10-05"));
    var licensed = new com.dealanalyzer.trade.valuation.TradeValuationAdapter.Provider("LICENSED_TEST", "v1", "TEST", false);
    var range = new com.dealanalyzer.trade.valuation.TradeValuationAdapter.Range(1_400_000, 1_600_000, 1_800_000, "USD");
    when(valuations.value(anyString(), any(TradeVehicle.class), anyString())).thenReturn(
        new com.dealanalyzer.trade.valuation.TradeValuationAdapter.Result(
            com.dealanalyzer.trade.valuation.TradeValuationAdapter.Availability.AVAILABLE, null, licensed, CLOCK.instant(),
            CLOCK.instant(), Condition.GOOD, range, BigDecimal.ZERO));
    when(policies.hasPolicy("US-NC")).thenReturn(true);
    when(policies.requirePolicy("US-NC")).thenReturn(policy("US-NC.cash.v1", "US-NC", "0.03"));
    var desk = new TradeDeskCheck(trades, valuations, policies, null, CLOCK) {
      @Override CaseFacts facts(String caseId) { return new CaseFacts(jurisdiction, "27604"); }
    };
    recorded(estimates, MoneyOwed.NO, null, null, false);
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    assertThat(result.valuation().basis()).isEqualTo("PROVIDER");
    assertThat(result.valuation().source()).isEqualTo("LICENSED_TEST");
    assertThat(result.findings().stream().map(TradeDeskCheck.Finding::summary)).noneMatch(text -> text.contains("Carfax"));

    // Without an estimate, the development range is still shown as before.
    recorded(MoneyOwed.NO, null, null, false);
    var fake = check(Scenario.NORMAL).check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null);
    assertThat(fake.valuation().basis()).isEqualTo("PROVIDER");
    assertThat(fake.valuation().synthetic()).isTrue();
  }

  @Test void aShortfallIsNeverADiscountAndAnAllowanceAboveValueIsShownNextToThePriceIncrease() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.NO, null, null, false, offer("best", 1_400_000, "Synthetic Car Exchange", false));
    var agreed = terms("SELLING_PRICE", 2_500_000, "DISCOUNT_TOTAL", -100_000);
    // $2,000 more for the trade, paid for by a $1,500 higher price and a $1,000 smaller discount.
    var result = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_600_000, "SELLING_PRICE", 2_650_000,
        "DISCOUNT_TOTAL", 0), agreed);
    var together = result.priceTogether();
    assertThat(together.reference()).isEqualTo("WRITTEN_OFFER");
    assertThat(together.allowanceAboveValueMinor()).isEqualTo(200_000);
    assertThat(together.priceIncreaseMinor()).isEqualTo(250_000);
    assertThat(together.netEffectMinor()).isEqualTo(-50_000);
    var finding = result.leading().stream().filter(item -> item.code().equals("ALLOWANCE_OFFSET_BY_PRICE")).findFirst().orElseThrow();
    assertThat(finding.summary()).contains("$2,000 above", "$2,500 higher than agreed", "$500 behind");
    assertThat(finding.amountMinor()).isEqualTo(50_000);

    // The record carries no discount figure; a shortfall is only ever a trade shortfall.
    assertThat(Arrays.stream(TradeDeskCheck.Result.class.getRecordComponents()).map(component -> component.getName()))
        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("discount"));
  }

  @Test void theTradeInTaxCreditComesFromTheJurisdictionPolicyAndIsUnavailableWithoutOne() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.NO, null, null, false, offer("best", 1_400_000, "Synthetic Car Exchange", false));
    jurisdiction = "US-FL";
    when(policies.hasPolicy("US-FL")).thenReturn(false);
    var missing = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "SELLING_PRICE", 2_500_000), null);
    assertThat(missing.taxCredit().status()).isEqualTo("UNAVAILABLE");
    assertThat(missing.taxCredit().creditMinor()).isNull();
    assertThat(missing.offerGapAfterTaxMinor()).isNull();
    assertThat(missing.leading().getFirst().summary()).contains("no tax rules are configured for US-FL");

    // A labelled synthetic Florida-shaped pack: the credit follows the pack's own rate and flag, not code.
    when(policies.hasPolicy("US-FL")).thenReturn(true);
    when(policies.requirePolicy("US-FL")).thenReturn(policy("US-FL.synthetic-test.v1", "US-FL", "0.06"));
    var configured = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "SELLING_PRICE", 2_500_000), null);
    assertThat(configured.taxCredit().policyVersion()).isEqualTo("US-FL.synthetic-test.v1");
    assertThat(configured.taxCredit().creditMinor()).isEqualTo(69_000);
    assertThat(configured.offerGapAfterTaxMinor()).isEqualTo(250_000 - 69_000);

    when(policies.requirePolicy("US-FL")).thenReturn(new Policy("US-FL.no-credit-test.v1", "US-FL", Currency.getInstance("USD"),
        new BigDecimal("0.06"), Set.of(LineType.SELLING_PRICE), false, 2, RoundingMode.HALF_UP, BigDecimal.ONE));
    var disabled = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "SELLING_PRICE", 2_500_000), null);
    assertThat(disabled.taxCredit().status()).isEqualTo("NOT_APPLICABLE");
    assertThat(disabled.offerGapAfterTaxMinor()).isEqualTo(250_000);
  }

  @Test void withoutARecordedTradeOrAllowanceTheCheckSaysSo() {
    var desk = check(Scenario.NORMAL);
    assertThat(desk.check("case", "token", terms("SELLING_PRICE", 2_500_000), null).status()).isEqualTo("NO_ALLOWANCE");
    verifyNoInteractions(trades);
    when(trades.view(anyString(), anyString())).thenReturn(new View("CASE", null, null, List.of()));
    var unrecorded = desk.check("case", "token", terms("TRADE_ALLOWANCE", 800_000), null);
    assertThat(unrecorded.status()).isEqualTo("TRADE_NOT_RECORDED");
    assertThat(codes(unrecorded)).containsExactly("TRADE_NOT_RECORDED");
    // A trade line on its own is read as the allowance.
    ObjectNode lines = (ObjectNode) terms();
    lines.withArray("lineItems").addObject().put("category", "TRADE").put("amountMinor", -900_000);
    assertThat(desk.check("case", "token", lines, null).allowanceMinor()).isEqualTo(900_000);
  }

  @Test void repeatedChecksReuseOneValuationPerTradeRevision() {
    var desk = check(Scenario.NORMAL);
    recorded(MoneyOwed.NO, null, null, false);
    desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000), null);
    desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_500_000), null);
    verify(valuations, times(1)).value(anyString(), any(TradeVehicle.class), anyString());
  }

  @Test void everySummaryStaysWithinTheClaimVocabulary() throws Exception {
    var termsOf = Class.forName("com.dealanalyzer.explanation.ClaimVocabulary").getDeclaredMethod("terms", String.class);
    termsOf.setAccessible(true);
    List<TradeDeskCheck.Finding> all = new ArrayList<>();
    var desk = check(Scenario.NORMAL);
    var offer = offer("best", 1_400_000, "Synthetic Car Exchange", false);
    var agreed = terms("SELLING_PRICE", 2_500_000);
    recorded(MoneyOwed.YES, 1_800_000L, TODAY, false, offer);
    for (long allowance : new long[] {1_150_000, 1_500_000, 1_900_000})
      for (long payoff : new long[] {1_800_000, 1_920_000})
        all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", allowance, "TRADE_PAYOFF", payoff, "TRADE_VIN", TRADE_VIN,
            "SELLING_PRICE", 2_650_000, "AMOUNT_FINANCED", 3_000_000, "APR", "6.9", "TERM_MONTHS", 72), agreed).findings());
    all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_VIN", OTHER_VIN), null).findings());
    recorded(MoneyOwed.YES, 1_800_000L, "2026-10-01", true);
    all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_PAYOFF", 1_000_000), null).findings());
    recorded(MoneyOwed.NOT_SURE, null, null, false);
    all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null).findings());
    recorded(MoneyOwed.NO, null, null, false);
    all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "TRADE_PAYOFF", 1_000), null).findings());
    all.addAll(check(Scenario.UNAVAILABLE).check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000), null).findings());
    var noRange = check(Scenario.UNAVAILABLE);
    for (var estimates : List.of(List.of(estimate("range", "Carfax", 1_400_000, 1_600_000L, "2026-08-01")),
        List.of(estimate("single", "Carfax", 1_500_000, null, "2026-10-05")))) {
      recorded(estimates, MoneyOwed.NO, null, null, false);
      for (long allowance : new long[] {1_150_000, 1_500_000, 1_900_000})
        all.addAll(noRange.check("case", "token", terms("TRADE_ALLOWANCE", allowance, "SELLING_PRICE", 2_650_000), agreed).findings());
    }
    jurisdiction = "US-FL";
    recorded(MoneyOwed.NO, null, null, false, offer);
    all.addAll(desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "SELLING_PRICE", 2_500_000), null).findings());
    // Ticket 137: the states' own tax names, the credit explanation and North Carolina's titling note.
    List<String> copy = new ArrayList<>();
    for (var pack : List.of(named("US-TX", "0.0625", "Texas motor vehicle sales tax", false),
        named("US-NC", "0.03", "North Carolina highway use tax", true))) {
      jurisdiction = pack.jurisdiction();
      when(policies.hasPolicy(pack.jurisdiction())).thenReturn(true);
      when(policies.requirePolicy(pack.jurisdiction())).thenReturn(pack);
      recorded(MoneyOwed.NO, null, null, false, offer);
      var named = desk.check("case", "token", terms("TRADE_ALLOWANCE", 1_150_000, "SELLING_PRICE", 2_500_000), null);
      assertThat(named.taxCredit().taxName()).isEqualTo(pack.taxRules().taxName());
      assertThat(named.findings().stream().map(TradeDeskCheck.Finding::summary))
          .anyMatch(text -> text.contains("lowers the " + pack.taxRules().taxName() + " on this car"));
      all.addAll(named.findings());
      copy.add(named.taxCredit().reason());
      if (pack.taxRules().buyerNote() != null) copy.add(pack.taxRules().buyerNote());
    }
    for (String text : copy) {
      @SuppressWarnings("unchecked") var terms = ((List<Object>) termsOf.invoke(null, text)).stream().map(Object::toString).toList();
      assertThat(terms).as(text).doesNotContain("STRONG", "FAIR", "POOR", "EXACT", "AVAILABLE", "DIFFERS", "UNAVAILABLE");
    }

    assertThat(all.stream().map(TradeDeskCheck.Finding::code).distinct().count()).isGreaterThanOrEqualTo(15);
    for (var finding : all) {
      @SuppressWarnings("unchecked") var terms = ((List<Object>) termsOf.invoke(null, finding.summary())).stream()
          .map(Object::toString).toList();
      assertThat(terms).as("no rating or certainty claim: " + finding.summary())
          .doesNotContain("STRONG", "FAIR", "POOR", "EXACT", "AVAILABLE");
      if (finding.estimate()) assertThat(terms).as(finding.summary()).contains("ESTIMATED");
      String code = finding.code();
      if (code.contains("BELOW")) assertThat(terms).as(finding.summary()).contains("BELOW");
      if (code.startsWith("ABOVE") || code.equals("ALLOWANCE_ABOVE_VALUE")) assertThat(terms).as(finding.summary()).contains("ABOVE");
      if (code.startsWith("WITHIN")) assertThat(terms).as(finding.summary()).contains("WITHIN");
      if (code.endsWith("STALE")) assertThat(terms).as(finding.summary()).contains("STALE");
      if (code.endsWith("UNKNOWN") || code.endsWith("UNAVAILABLE")) assertThat(terms).as(finding.summary()).contains("UNAVAILABLE");
      if (code.endsWith("SAME")) assertThat(terms).as(finding.summary()).contains("MATCH");
      if (code.endsWith("DIFFERS")) assertThat(terms).as(finding.summary()).containsAnyOf("ABOVE", "BELOW", "DIFFERS");
    }
  }

  /** A pack that names its tax, as V61's US-TX and US-NC packs do. */
  private static Policy named(String place, String rate, String taxName, boolean titling) {
    return new Policy(place + ".synthetic-named.v1", place, Currency.getInstance("USD"), new BigDecimal(rate),
        Set.of(LineType.SELLING_PRICE, LineType.DEALER_FEE, LineType.ADD_ON), true, 2, RoundingMode.HALF_UP, BigDecimal.ONE,
        List.of(new CashDealEconomics.TaxComponent(taxName, new BigDecimal(rate), CashDealEconomics.TaxBase.TAXABLE_PRICE_AFTER_TRADE,
            null, "synthetic fixture", "2026-10-16")),
        new CashDealEconomics.TaxRules(taxName, java.util.Map.of(), titling,
            titling ? "North Carolina collects the highway use tax when the car is titled. The dealer may collect it with the sale, "
                + "or you pay it when you title the car, so a quote can leave it out. It is still owed, and the total here includes it." : null));
  }

  private static Policy policy(String version, String place, String rate) {
    return new Policy(version, place, Currency.getInstance("USD"), new BigDecimal(rate),
        Set.of(LineType.SELLING_PRICE, LineType.DEALER_FEE, LineType.ADD_ON), true, 2, RoundingMode.HALF_UP, BigDecimal.ONE);
  }
}
