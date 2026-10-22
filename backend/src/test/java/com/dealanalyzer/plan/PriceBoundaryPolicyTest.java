package com.dealanalyzer.plan;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PriceBoundaryPolicyTest {
  @Test
  void convertsEachBoundaryUsingTaxAndAppliesIndependentBuyerCaps() {
    Currency usd = Currency.getInstance("USD");
    var policy = new Policy("tax.v1", "fixture", usd, new BigDecimal("0.10"),
        Set.of(LineType.SELLING_PRICE), false, 2, RoundingMode.HALF_UP, BigDecimal.ZERO);
    var offer = new Offer("offer-1", 1, usd, List.of(
        new LineItem("price", "Selling price", new Money(new BigDecimal("10000"), usd),
            LineType.SELLING_PRICE, TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE)), null, null);
    var deal = new CashDealEconomicsCalculator().calculate(offer, policy);
    var market = new MarketAssessmentView("market", "case", "v1", 1, "AVAILABLE",
        new RangeView(900_000, 1_100_000, "USD"), "fixture", null, 4, "market.v1",
        "MEDIUM", "Recorded", null, List.of(), List.of(), Instant.now());
    var plan = new PriceBoundaryPolicy().calculate(market, deal, 950_000L, 1_000_000L);
    assertThat(plan.sellingPrice().openingMinor()).isEqualTo(900_000);
    assertThat(plan.sellingPrice().targetMinor()).isEqualTo(1_000_000);
    assertThat(plan.sellingPrice().policyMaximumMinor()).isEqualTo(1_100_000);
    assertThat(plan.sellingPrice().walkAwayMinor()).isEqualTo(950_000);
    assertThat(plan.outTheDoor().openingMinor()).isEqualTo(990_000);
    assertThat(plan.outTheDoor().targetMinor()).isEqualTo(1_100_000);
    assertThat(plan.outTheDoor().policyMaximumMinor()).isEqualTo(1_210_000);
    assertThat(plan.outTheDoor().walkAwayMinor()).isEqualTo(1_000_000);
    assertThatThrownBy(() -> new PriceBoundaryPolicy().calculate(market, deal, -1L, null))
        .isInstanceOf(IllegalArgumentException.class);

    // Ticket 141: without a checked tax rate there is no OTD conversion; selling-price targets and caps remain.
    var crossState = new PriceBoundaryPolicy().calculate(market,
        new CashDealEconomicsCalculator().calculate(offer, policy, "US-TX"), 950_000L, 1_000_000L);
    assertThat(crossState.outTheDoor()).isNull();
    assertThat(crossState.sellingPrice()).isEqualTo(plan.sellingPrice());
    assertThat(crossState.buyerMaxOutTheDoorPriceMinor()).isEqualTo(1_000_000);
    assertThat(crossState.warnings()).anySatisfy(warning ->
        assertThat(warning).contains("tax is not checked for a car registered in another state"));
  }
}
