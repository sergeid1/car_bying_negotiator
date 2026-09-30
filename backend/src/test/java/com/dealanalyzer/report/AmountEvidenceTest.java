package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.economics.CashDealEconomics.Eligibility;
import com.dealanalyzer.economics.CashDealEconomics.LineItem;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.Money;
import com.dealanalyzer.economics.CashDealEconomics.Offer;
import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.finance.FinanceLoan;
import com.dealanalyzer.finance.FinancingCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AmountEvidenceTest {
  private static final Currency USD = Currency.getInstance("USD");
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void documentAmountsResolveToTheirPageAndRegionAndCalculatedAmountsAddUpByHand() {
    Offer offer = new Offer("offer-1", 2, USD, List.of(
        line("line-price", "Vehicle price", "25000.00", LineType.SELLING_PRICE, TaxTreatment.TAXABLE),
        line("line-discount", "Dealer discount", "500.00", LineType.DEALER_DISCOUNT, TaxTreatment.TAXABLE),
        line("line-doc", "Doc fee", "899.00", LineType.DEALER_FEE, TaxTreatment.TAXABLE),
        line("line-dmv", "DMV", "124.00", LineType.GOVERNMENT_CHARGE, TaxTreatment.NOT_TAXABLE),
        line("line-trade", "Trade allowance", "3000.00", LineType.TRADE_ALLOWANCE, TaxTreatment.TAX_CREDIT),
        line("line-cash", "Cash down", "1000.00", LineType.DOWN_PAYMENT, TaxTreatment.NOT_TAXABLE)),
        new Money(new BigDecimal("24500.00"), USD), "candidate-otd");
    Policy policy = new Policy("test-policy.v1", "US-TX", USD, new BigDecimal("0.0625"),
        Set.of(LineType.SELLING_PRICE, LineType.DEALER_FEE), true, 2, RoundingMode.HALF_UP, new BigDecimal("1.00"));
    JsonNode result = json.valueToTree(new CashDealEconomicsCalculator().calculate(offer, policy));
    JsonNode terms = json.valueToTree(Map.of(
        "sourceExtractionId", "extraction-1",
        "lineItems", List.of(term("line-price", "Vehicle price", 2), term("line-doc", "Documentation fee", 1)),
        "candidates", List.of(Map.of("sourceCandidateId", "candidate-otd", "originalWording", "Total due $24,500.00",
            "evidence", Map.of("pageNumber", 3, "x", 0.5, "y", 0.8, "width", 0.3, "height", 0.04)))));

    var evidence = new AmountEvidence().cash(result, terms, "document-1", 2);

    var price = evidence.find("cashEconomics", "/result/lineItems/0/amount");
    assertThat(price.documentSource().pageNumber()).isEqualTo(2);
    assertThat(price.documentSource().documentId()).isEqualTo("document-1");
    assertThat(price.documentSource().wording()).isEqualTo("Vehicle price");
    assertThat(price.documentSource().region()).isEqualTo(new AmountEvidence.Region(0.1, 0.2, 0.4, 0.05));
    var dmv = evidence.find("cashEconomics", "/result/lineItems/3/amount");
    assertThat(dmv.documentSource()).isNull();
    assertThat(dmv.sourceNote()).contains("No document region");
    assertThat(evidence.find("cashEconomics", "/result/reconciliation/quotedTotal").documentSource().pageNumber()).isEqualTo(3);

    for (String pointer : List.of("/result/vehicleSubtotal", "/result/dealerCharges", "/result/governmentCharges",
        "/result/outTheDoorTotal", "/result/netAmountDue", "/result/tradeTerms/netCredit",
        "/result/reconciliation/discrepancy", "/result/priceReductions/dealerDiscounts")) {
      var explanation = evidence.find("cashEconomics", pointer);
      assertThat(explanation.documentSource()).as(pointer).isNull();
      assertThat(explanation.sourceNote()).as(pointer).contains("Calculated");
      assertThat(evaluate(explanation.calculation())).as(pointer).isEqualTo(result.at(pointer.substring("/result".length()) + "/minorUnits").asLong());
    }
    var subtotal = evidence.find("cashEconomics", "/result/vehicleSubtotal").calculation().operands();
    assertThat(subtotal).extracting(AmountEvidence.Operand::operator).containsExactly(null, "−");
    assertThat(subtotal).extracting(AmountEvidence.Operand::label).containsExactly("Vehicle price", "Dealer discount");

    var tax = evidence.find("cashEconomics", "/result/taxes").calculation();
    assertThat(tax.operands()).extracting(AmountEvidence.Operand::operator).containsExactly(null, "×");
    assertThat(tax.operands().get(1).value()).isEqualTo("6.25%");
    assertThat(BigDecimal.valueOf(tax.operands().get(0).minorUnits()).multiply(new BigDecimal("0.0625"))
        .setScale(0, RoundingMode.HALF_UP).longValue()).isEqualTo(result.at("/taxes/minorUnits").asLong());
    assertThat(tax.notes()).anySatisfy(note -> assertThat(note).contains("trade tax credit applied"));
    assertThat(evidence.find("cashEconomics", "/result/reconciliation/tolerance").sourceNote()).contains("test-policy.v1");
  }

  @Test
  void financingAndMarketAmountsSayPlainlyThatNoDocumentSourceExists() {
    var loan = new FinanceLoan("USD", 2_400_000L, 2_500_000L, 2_000_000L, 2_010_000L,
        new BigDecimal("6.9"), 60, "STANDARD_FIXED_RATE", FinancingCalculator.TIMING, 10_000L, 5_000L,
        505_000L, 0L, 40_000L, List.of());
    JsonNode inputs = json.valueToTree(loan);
    JsonNode result = json.valueToTree(new FinancingCalculator().calculate(loan));

    var evidence = new AmountEvidence().financing(inputs, result).market("Fixture provider");

    assertThat(evidence.find("financing", "/inputs/purchasePriceMinor").sourceNote()).contains("no document region");
    assertThat(evaluate(evidence.find("financing", "/inputs/principalMinor").calculation())).isEqualTo(2_010_000L);
    assertThat(evaluate(evidence.find("financing", "/result/totalInterestMinor").calculation()))
        .isEqualTo(result.path("totalInterestMinor").asLong());
    assertThat(evaluate(evidence.find("financing", "/result/totalTransactionCostMinor").calculation()))
        .isEqualTo(result.path("totalTransactionCostMinor").asLong());
    var scheduled = evidence.find("financing", "/result/totalScheduledPaymentsMinor").calculation().operands();
    assertThat(scheduled.get(0).minorUnits() * Long.parseLong(scheduled.get(1).value()) + scheduled.get(2).minorUnits())
        .isEqualTo(result.path("totalScheduledPaymentsMinor").asLong());
    assertThat(evidence.find("financing", "/result/paymentMinor").calculation().operands())
        .extracting(AmountEvidence.Operand::value).contains("6.9%", "60");
    assertThat(evidence.find("marketAssessment", "/range/lowMinor").sourceNote())
        .contains("Fixture provider", "not read from a dealer document");
  }

  private static long evaluate(AmountEvidence.Calculation calculation) {
    long total = 0;
    for (var operand : calculation.operands()) {
      assertThat(operand.minorUnits()).as(operand.label()).isNotNull();
      total = "−".equals(operand.operator()) ? total - operand.minorUnits() : total + operand.minorUnits();
    }
    return total;
  }

  private static Map<String, Object> term(String id, String label, int page) {
    return Map.of("sourceLineItemId", id, "originalLabel", label,
        "evidence", Map.of("pageNumber", page, "x", 0.1, "y", 0.2, "width", 0.4, "height", 0.05));
  }

  private static LineItem line(String evidence, String label, String amount, LineType type, TaxTreatment tax) {
    return new LineItem(evidence, label, new Money(new BigDecimal(amount), USD), type, tax,
        type == LineType.REBATE ? Eligibility.UNVERIFIED : Eligibility.NOT_APPLICABLE);
  }
}
