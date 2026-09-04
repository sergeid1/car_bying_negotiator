package com.dealanalyzer.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.economics.CashDealEconomics.Eligibility;
import com.dealanalyzer.economics.CashDealEconomics.LineItem;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.Offer;
import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CashDealEconomicsCalculatorTest {
  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void calculatesCashEconomicsWithoutTreatingBuyerFundsOrConditionalRebatesAsDiscounts() {
    Offer offer = new Offer("offer-1", 3, USD, List.of(
        line("vehicle", "25000.00", LineType.SELLING_PRICE, TaxTreatment.TAXABLE),
        line("discount", "500.00", LineType.DEALER_DISCOUNT, TaxTreatment.TAXABLE),
        new LineItem("conditional-rebate", "Captive finance rebate", money("1000.00"),
            LineType.REBATE, TaxTreatment.TAXABLE, Eligibility.UNVERIFIED),
        line("doc-fee", "Dealer fee", "899.00", LineType.DEALER_FEE, TaxTreatment.TAXABLE),
        line("add-on", "Protection", "400.00", LineType.ADD_ON, TaxTreatment.TAXABLE),
        line("mystery", "Mystery charge", "75.00", LineType.UNKNOWN, TaxTreatment.UNKNOWN),
        line("dmv", "DMV", "124.00", LineType.GOVERNMENT_CHARGE, TaxTreatment.NOT_TAXABLE),
        line("trade", "Trade allowance", "30000.00", LineType.TRADE_ALLOWANCE,
            TaxTreatment.TAX_CREDIT),
        line("payoff", "Trade payoff", "20000.00", LineType.TRADE_PAYOFF,
            TaxTreatment.NOT_TAXABLE),
        line("cash", "Cash down", "1000.00", LineType.DOWN_PAYMENT,
            TaxTreatment.NOT_TAXABLE),
        line("bank", "Outside financing", "5000.00", LineType.OUTSIDE_FINANCING,
            TaxTreatment.NOT_TAXABLE),
        line("buyer-credit", "Buyer credit", "100.00", LineType.OTHER_BUYER_FUNDS,
            TaxTreatment.NOT_TAXABLE),
        line("dealer-credit", "Dealer-issued credit", "25.00", LineType.CREDIT,
            TaxTreatment.NOT_TAXABLE)), money("25998.00"), "quoted-total");

    var result = new CashDealEconomicsCalculator().calculate(offer, policy("0.03", "1.00"));

    assertThat(result.vehicleSubtotal().minorUnits()).isEqualTo(2_450_000);
    assertThat(result.dealerCharges().minorUnits()).isEqualTo(129_900);
    assertThat(result.taxes().minorUnits()).isZero();
    assertThat(result.governmentCharges().minorUnits()).isEqualTo(12_400);
    assertThat(result.unknownCharges().minorUnits()).isEqualTo(7_500);
    assertThat(result.outTheDoorTotal().minorUnits()).isEqualTo(2_599_800);
    assertThat(result.tradeTerms().allowance().minorUnits()).isEqualTo(3_000_000);
    assertThat(result.tradeTerms().payoff().minorUnits()).isEqualTo(2_000_000);
    assertThat(result.tradeTerms().netCredit().minorUnits()).isEqualTo(1_000_000);
    assertThat(result.downPayment().minorUnits()).isEqualTo(100_000);
    assertThat(result.credits().minorUnits()).isEqualTo(512_500);
    assertThat(result.netAmountDue().minorUnits()).isEqualTo(987_300);
    assertThat(result.priceReductions().guaranteed().minorUnits()).isEqualTo(50_000);
    assertThat(result.priceReductions().conditionalRebatesExcluded().minorUnits())
        .isEqualTo(100_000);
    assertThat(result.taxCalculation().tradeCreditApplied()).isEqualByComparingTo("25799.00");
    assertThat(result.lineItems()).filteredOn(item -> item.type() == LineType.UNKNOWN)
        .singleElement().satisfies(item -> assertThat(item.treatmentReason())
            .isEqualTo("Unknown charge retained in OTD and classified neutrally"));
  }

  @Test
  void retainsUnroundedTaxPrecisionAndReconcilesWithoutReplacingTheQuotedTotal() {
    Offer offer = new Offer("offer-2", 1, USD, List.of(
        line("vehicle", "100.50", LineType.SELLING_PRICE, TaxTreatment.UNKNOWN)),
        money("109.00"), "page-2-total");

    var result = new CashDealEconomicsCalculator().calculate(offer, policy("0.0725", "0.50"));

    assertThat(result.taxCalculation().unrounded()).isEqualByComparingTo("7.286250");
    assertThat(result.taxes().minorUnits()).isEqualTo(729);
    assertThat(result.outTheDoorTotal().minorUnits()).isEqualTo(10_779);
    assertThat(result.rounding().mode()).isEqualTo(RoundingMode.HALF_UP);
    assertThat(result.rounding().appliedAt()).isEqualTo("TAX_AND_FINAL_MONETARY_OUTPUTS");
    assertThat(result.reconciliation().quotedTotal().minorUnits()).isEqualTo(10_900);
    assertThat(result.reconciliation().recomputedTotal().minorUnits()).isEqualTo(10_779);
    assertThat(result.reconciliation().discrepancy().minorUnits()).isEqualTo(121);
    assertThat(result.reconciliation().withinTolerance()).isFalse();
    assertThat(result.reconciliation().evidence()).isEqualTo("page-2-total");
    assertThat(result.reconciliation().reason())
        .isEqualTo("Quoted total differs from the policy recomputation; no favorable classification inferred");
  }

  @Test
  void canonicalGoldenFixtureCalculatesCashPurchaseWithoutFinancingQuestions() throws Exception {
    JsonNode fixture = new ObjectMapper().readTree(getClass().getResourceAsStream(
        "/fixtures/economics/canonical-cash-economics.json"));
    JsonNode input = fixture.path("offer");
    JsonNode expected = fixture.path("expected");
    Offer offer = new Offer("golden-offer", 1, USD, List.of(line(
        "vehicle", input.path("sellingPrice").asText(), LineType.SELLING_PRICE,
        TaxTreatment.TAXABLE)), money(input.path("quotedOtd").asText()),
        input.path("evidence").asText());
    var result = new CashDealEconomicsCalculator().calculate(offer, policy(
        fixture.at("/policy/taxRate").asText(), fixture.at("/policy/tolerance").asText()));

    assertThat(result.taxCalculation().unrounded())
        .isEqualByComparingTo(expected.path("unroundedTax").asText());
    assertThat(result.taxes().minorUnits()).isEqualTo(expected.path("taxMinor").asLong());
    assertThat(result.outTheDoorTotal().minorUnits()).isEqualTo(expected.path("otdMinor").asLong());
    assertThat(result.reconciliation().discrepancy().minorUnits())
        .isEqualTo(expected.path("discrepancyMinor").asLong());
    assertThat(result.lineItems()).noneMatch(item -> item.type() == LineType.OUTSIDE_FINANCING);
  }

  @Test
  void propertyFixtureCoversTaxCreditCapsAndBuyerFundsForManyCashOffers() throws Exception {
    JsonNode fixture = new ObjectMapper().readTree(getClass().getResourceAsStream(
        "/fixtures/economics/cash-economics-properties.json"));
    Random random = new Random(fixture.path("seed").asLong());
    int maximum = fixture.path("maximumMinorUnits").asInt();
    for (int index = 0; index < fixture.path("iterations").asInt(); index++) {
      long price = 1 + random.nextInt(maximum);
      long trade = random.nextInt(maximum);
      long buyerFunds = random.nextInt(maximum);
      Offer offer = new Offer("property-" + index, 1, USD, List.of(
          new LineItem("vehicle", "vehicle", fromMinor(price), LineType.SELLING_PRICE,
              TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE),
          new LineItem("trade", "trade", fromMinor(trade), LineType.TRADE_ALLOWANCE,
              TaxTreatment.TAX_CREDIT, Eligibility.NOT_APPLICABLE),
          new LineItem("funds", "funds", fromMinor(buyerFunds), LineType.OTHER_BUYER_FUNDS,
              TaxTreatment.NOT_TAXABLE, Eligibility.NOT_APPLICABLE)), null, null);

      var result = new CashDealEconomicsCalculator().calculate(offer, policy("0.03", "1.00"));

      assertThat(result.taxCalculation().tradeCreditApplied())
          .isLessThanOrEqualTo(result.taxCalculation().taxableCharges());
      assertThat(result.outTheDoorTotal().minorUnits())
          .isEqualTo(result.vehicleSubtotal().minorUnits() + result.taxes().minorUnits());
      assertThat(result.netAmountDue().minorUnits())
          .isEqualTo(result.outTheDoorTotal().minorUnits()
              - result.tradeTerms().netCredit().minorUnits() - result.credits().minorUnits());
      assertThat(result.priceReductions().guaranteed().minorUnits()).isZero();
    }
  }

  @Test
  void rejectsMixedCurrenciesBeforeCalculating() {
    Currency cad = Currency.getInstance("CAD");
    Offer offer = new Offer("mixed", 1, USD, List.of(new LineItem(
        "vehicle", "vehicle", new CashDealEconomics.Money(new BigDecimal("100.00"), cad),
        LineType.SELLING_PRICE, TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE)), null, null);

    assertThatThrownBy(() -> new CashDealEconomicsCalculator().calculate(
        offer, policy("0.03", "1.00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mixed currencies are not supported");
  }

  private static Policy policy(String taxRate, String tolerance) {
    return new Policy("US-NC.cash.v1", "US-NC", USD, new BigDecimal(taxRate),
        Set.of(LineType.SELLING_PRICE, LineType.DEALER_FEE, LineType.ADD_ON), true,
        2, RoundingMode.HALF_UP, new BigDecimal(tolerance));
  }

  private static LineItem line(
      String evidence, String amount, LineType type, TaxTreatment taxTreatment) {
    return line(evidence, type.name(), amount, type, taxTreatment);
  }

  private static LineItem line(
      String evidence, String label, String amount, LineType type, TaxTreatment taxTreatment) {
    return new LineItem(evidence, label, money(amount), type, taxTreatment, Eligibility.NOT_APPLICABLE);
  }

  private static CashDealEconomics.Money money(String amount) {
    return new CashDealEconomics.Money(new BigDecimal(amount), USD);
  }

  private static CashDealEconomics.Money fromMinor(long amount) {
    return new CashDealEconomics.Money(BigDecimal.valueOf(amount, 2), USD);
  }
}
