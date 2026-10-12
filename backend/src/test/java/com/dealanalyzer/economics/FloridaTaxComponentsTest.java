package com.dealanalyzer.economics;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.*;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Labelled synthetic prices and trades, never real Buyer paperwork. */
class FloridaTaxComponentsTest {
  private static final Currency USD = Currency.getInstance("USD");

  @ParameterizedTest
  @CsvSource({"4000,0,.01,28000", "35000,0,.01,215000", "8000,4000,.01,28000",
      "35000,0,0,210000", "35000,12000,.01,143000", "4000,8000,.01,0"})
  void syntheticPricesTradeAllowancesAndZeroSurtaxAreCalculated(String price, String trade, String rate, long tax) {
    var result = calculate(price, trade, policy(rate));
    assertThat(result.taxes().minorUnits()).isEqualTo(tax);
    assertThat(result.taxCalculation().components()).hasSize(2);
    assertThat(result.taxCalculation().components().get(1).tax(result.taxCalculation().taxableBasis()))
        .isLessThanOrEqualTo(new BigDecimal("5000").multiply(new BigDecimal(rate)));
  }

  @Test void agreedPriceBoundariesRecomputeTheSurtaxWhenTheTradeBringsThePriceAcrossTheCap() {
    var result = calculate("10000", "4000", policy(".01"));
    var market = new MarketAssessmentView("synthetic-market", "synthetic-case", "v1", 1, "AVAILABLE",
        new RangeView(800_000, 1_200_000, "USD"), "synthetic fixture", null, 4, "market.v1",
        "MEDIUM", "Recorded", null, List.of(), List.of(), Instant.EPOCH);
    var boundaries = new PriceBoundaryPolicy().calculate(market, result, null, null).outTheDoor();
    assertThat(boundaries.openingMinor()).isEqualTo(828_000); // $4,000 taxable: $240 + $40
    assertThat(boundaries.targetMinor()).isEqualTo(1_041_000); // $6,000 taxable: $360 + $50
    assertThat(boundaries.policyMaximumMinor()).isEqualTo(1_253_000); // $8,000 taxable: $480 + $50
  }

  private static Result calculate(String price, String trade, Policy policy) {
    var offer = new Offer("synthetic-florida-offer", 1, USD, List.of(
        new LineItem("synthetic-price", "Synthetic selling price", new Money(new BigDecimal(price), USD),
            LineType.SELLING_PRICE, TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE),
        new LineItem("synthetic-trade", "Synthetic trade allowance", new Money(new BigDecimal(trade), USD),
            LineType.TRADE_ALLOWANCE, TaxTreatment.TAX_CREDIT, Eligibility.NOT_APPLICABLE)), null, null);
    return new CashDealEconomicsCalculator().calculate(offer, policy);
  }

  private static Policy policy(String countyRate) {
    return new Policy("synthetic-florida-components", "US-FL:Synthetic", USD, new BigDecimal(".06"),
        Set.of(LineType.SELLING_PRICE), true, 2, RoundingMode.HALF_UP, BigDecimal.ONE, List.of(
        new TaxComponent("Synthetic state", new BigDecimal(".06"), TaxBase.TAXABLE_PRICE_AFTER_TRADE,
            null, "synthetic fixture", "2026-10-12"),
        new TaxComponent("Synthetic county", new BigDecimal(countyRate), TaxBase.TAXABLE_PRICE_AFTER_TRADE,
            new BigDecimal("5000"), "synthetic fixture", "2026-10-12")));
  }
}
