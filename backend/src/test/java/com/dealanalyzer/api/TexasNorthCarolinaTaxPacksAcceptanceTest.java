package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.*;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ticket 137: the sourced US-TX and US-NC packs as V61 stores them, run through the shared calculation with
 * labelled synthetic prices, trades and dealer charges, never real Buyer paperwork.
 */
class TexasNorthCarolinaTaxPacksAcceptanceTest {
  private static final Currency USD = Currency.getInstance("USD");
  @TempDir Path directory;

  @Test void theSourcedPacksTaxThePriceLessTheTradeAndTheirOwnTaxableCharges() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("texas-north-carolina-packs")) {
      var policies = app.context().getBean(CashEconomicsPersistence.class);
      var database = app.context().getBean(JdbcTemplate.class);
      Policy texas = policies.requirePolicy("US-TX"), carolina = policies.requirePolicy("US-NC");

      // Effective-dated, cited and checked; each pack has a review date.
      assertThat(texas.version()).isEqualTo("US-TX.cash.2026.v1");
      assertThat(carolina.version()).as("no North Carolina Buyer gets the synthetic fixture").isEqualTo("US-NC.cash.2026.v1");
      for (Policy policy : List.of(texas, carolina)) {
        assertThat(policy.taxComponents()).singleElement().satisfies(component -> {
          assertThat(component.base()).isEqualTo(TaxBase.TAXABLE_PRICE_AFTER_TRADE);
          assertThat(component.baseCap()).as("the NC $2,000 maximum is for commercial and recreational vehicles only").isNull();
          assertThat(component.citation()).contains("https://");
          assertThat(component.checkedOn()).isEqualTo("2026-10-16");
        });
        assertThat(policy.tradeTaxCreditEnabled()).isTrue();
        String json = database.queryForObject("select policy_json from jurisdiction_policies where policy_version = ?",
            String.class, policy.version());
        assertThat(json).contains("\"reviewOn\":\"2027-01-01\"", "\"tradeCreditCitation\":", "\"taxableLinesCitation\":");
      }
      assertThat(texas.taxComponents().getFirst().rate()).isEqualByComparingTo(".0625");
      assertThat(texas.taxComponents().getFirst().citation()).contains("152.021", "96-254");
      assertThat(carolina.taxComponents().getFirst().rate()).isEqualByComparingTo(".03");
      assertThat(carolina.taxComponents().getFirst().citation()).contains("105-187.3", "MVR-106");
      assertThat(texas.taxRules().taxName()).isEqualTo("Texas motor vehicle sales tax");
      assertThat(texas.taxRules().paidAtTitling()).isFalse();
      assertThat(carolina.taxRules().taxName()).isEqualTo("North Carolina highway use tax");
      assertThat(carolina.taxRules().paidAtTitling()).isTrue();

      // The fixture keeps its version for frozen results, under a jurisdiction no postal code resolves to.
      assertThat(database.queryForObject("select jurisdiction from jurisdiction_policies where policy_version = 'US-NC.cash.v1'",
          String.class)).isEqualTo("SYNTHETIC-FIXTURE");
      assertThat(database.queryForObject("select count(*) from postal_jurisdictions where jurisdiction not like 'US-%'",
          Integer.class)).isZero();

      // No trade; a trade that reduces the taxable price; a trade larger than the price floors the tax at zero.
      assertThat(tax(texas, "30000", "0")).isEqualTo(187_500);
      assertThat(tax(carolina, "30000", "0")).isEqualTo(90_000);
      assertThat(tax(texas, "30000", "12000")).isEqualTo(112_500);
      assertThat(tax(carolina, "30000", "12000")).isEqualTo(54_000);
      assertThat(tax(texas, "8000", "10000")).isZero();
      assertThat(tax(carolina, "8000", "10000")).isZero();
      // The Comptroller's own example in 96-254, Motor Vehicle Sales Tax: $42,000 less a $15,000 trade-in.
      assertThat(tax(texas, "42000", "15000")).isEqualTo(168_750);

      // Each state's own taxable dealer charges. Texas excludes a separately stated documentary fee; North
      // Carolina taxes the G.S. 20-101.1 administrative fee. Neither taxes a separately stated service contract or
      // GAP waiver; both tax dealer preparation and an accessory attached at the sale.
      var texasCharges = calculate(texas, "30000", "0", null, charges());
      assertThat(texasCharges.taxCalculation().taxableCharges()).isEqualByComparingTo("30700");
      assertThat(texasCharges.taxes().minorUnits()).isEqualTo(191_875);
      assertThat(texasCharges.lineItems().stream().filter(line -> line.label().equals("Documentary fee")).findFirst()
          .orElseThrow().treatmentReason()).contains("Not taxable under US-TX.cash.2026.v1", "doc");
      var carolinaCharges = calculate(carolina, "30000", "0", null, charges());
      assertThat(carolinaCharges.taxCalculation().taxableCharges()).isEqualByComparingTo("30850");
      assertThat(carolinaCharges.taxes().minorUnits()).isEqualTo(92_550);
      assertThat(carolinaCharges.lineItems().stream().filter(line -> line.label().equals("Extended service contract"))
          .findFirst().orElseThrow().treatmentReason()).contains("Not taxable under US-NC.cash.2026.v1");

      // North Carolina collects the highway use tax at titling, so a quote without it is not a mismatch. Texas
      // dealers collect the tax, so the same shortfall there still differs.
      var withoutTax = calculate(carolina, "30000", "12000", new BigDecimal("30000"), List.of());
      assertThat(withoutTax.reconciliation().withinTolerance()).isTrue();
      assertThat(withoutTax.reconciliation().reason()).contains("North Carolina highway use tax", "titled");
      assertThat(withoutTax.outTheDoorTotal().minorUnits()).as("the tax is still counted").isEqualTo(3_054_000);
      assertThat(withoutTax.taxCalculation().buyerNote()).contains("still owed");
      assertThat(calculate(carolina, "30000", "12000", new BigDecimal("30540"), List.of()).reconciliation().withinTolerance()).isTrue();
      assertThat(calculate(texas, "30000", "12000", new BigDecimal("30000"), List.of()).reconciliation().withinTolerance()).isFalse();

      // The trade comparison: the tax the Buyer loses by selling the car elsewhere instead of trading it in.
      assertThat(credit(texas, "30000", "12000")).isEqualByComparingTo("750.00");
      assertThat(credit(carolina, "30000", "12000")).isEqualByComparingTo("360.00");

      // Agreed Price boundaries recompute the state tax at each price, less the same $4,000 trade.
      var market = new MarketAssessmentView("synthetic-market", "synthetic-case", "v1", 1, "AVAILABLE",
          new RangeView(800_000, 1_200_000, "USD"), "synthetic fixture", null, 4, "market.v1",
          "MEDIUM", "Recorded", null, List.of(), List.of(), Instant.EPOCH);
      var texasLimits = new PriceBoundaryPolicy().calculate(market, calculate(texas, "10000", "4000", null, List.of()), null, null).outTheDoor();
      assertThat(texasLimits.openingMinor()).isEqualTo(825_000); // $8,000 + 6.25% of $4,000
      assertThat(texasLimits.targetMinor()).isEqualTo(1_037_500); // $10,000 + 6.25% of $6,000
      assertThat(texasLimits.policyMaximumMinor()).isEqualTo(1_250_000); // $12,000 + 6.25% of $8,000
      var carolinaLimits = new PriceBoundaryPolicy().calculate(market, calculate(carolina, "10000", "4000", null, List.of()), null, null).outTheDoor();
      assertThat(carolinaLimits.openingMinor()).isEqualTo(812_000);
      assertThat(carolinaLimits.targetMinor()).isEqualTo(1_018_000);
      assertThat(carolinaLimits.policyMaximumMinor()).isEqualTo(1_224_000);

      // A malformed rule fails loading rather than silently taxing everything.
      database.update("insert into jurisdiction_policies select 'malformed-test', 'US-GA', currency, tax_rate, taxable_line_types, "
          + "trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance, "
          + "replace(policy_json, '\"DEALER_FEE\":[', '\"NOT_A_LINE\":['), effective_at from jurisdiction_policies "
          + "where policy_version = 'US-TX.cash.2026.v1'");
      assertThatThrownBy(() -> policies.requirePolicy("US-GA")).hasMessageContaining("Unable to read jurisdiction tax rules");
    }
  }

  private static List<LineItem> charges() {
    return List.of(line("Documentary fee", "150", LineType.DEALER_FEE), line("Dealer prep", "300", LineType.DEALER_FEE),
        line("Window tint", "400", LineType.ADD_ON), line("Extended service contract", "2000", LineType.ADD_ON),
        line("GAP waiver", "700", LineType.ADD_ON));
  }

  private static long tax(Policy policy, String price, String trade) {
    return calculate(policy, price, trade, null, List.of()).taxes().minorUnits();
  }

  private static BigDecimal credit(Policy policy, String price, String trade) {
    BigDecimal basis = new BigDecimal(price), withTrade = basis.subtract(new BigDecimal(trade));
    return CashDealEconomicsCalculator.taxAt(basis, policy).setScale(2, policy.roundingMode())
        .subtract(CashDealEconomicsCalculator.taxAt(withTrade, policy).setScale(2, policy.roundingMode()));
  }

  private static Result calculate(Policy policy, String price, String trade, BigDecimal quoted, List<LineItem> extra) {
    var lines = new ArrayList<LineItem>();
    lines.add(line("Synthetic selling price", price, LineType.SELLING_PRICE));
    lines.add(new LineItem("synthetic-trade", "Synthetic trade allowance", new Money(new BigDecimal(trade), USD),
        LineType.TRADE_ALLOWANCE, TaxTreatment.TAX_CREDIT, Eligibility.NOT_APPLICABLE));
    lines.addAll(extra);
    var offer = new Offer("synthetic-" + policy.jurisdiction(), 1, USD, lines,
        quoted == null ? null : new Money(quoted, USD), quoted == null ? null : "synthetic-quoted-total");
    return new CashDealEconomicsCalculator().calculate(offer, policy);
  }

  private static LineItem line(String label, String amount, LineType type) {
    return new LineItem("synthetic-" + label, label, new Money(new BigDecimal(amount), USD), type,
        TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE);
  }
}
