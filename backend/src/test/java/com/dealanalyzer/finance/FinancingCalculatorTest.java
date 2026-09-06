package com.dealanalyzer.finance;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FinancingCalculatorTest {
  private final FinancingCalculator calculator = new FinancingCalculator();
  private final ObjectMapper json = new ObjectMapper();
  private FinanceLoan loan(long principal, String apr, int months, long addon) {
    return new FinanceLoan("USD", principal, principal, principal, principal, new BigDecimal(apr), months,
        "STANDARD_FIXED_RATE", FinancingCalculator.TIMING, 0L, 10000L, 210000L, 5000L,
        90000L, List.of(new FinanceLoan.AddOn("Protection", addon)));
  }
  @Test void frozenGoldenLoansIncludeZeroAprMismatchAndFinancedAddOns() throws Exception {
    var fixtures = json.readTree(getClass().getResourceAsStream("/fixtures/financing/golden-loans.json"));
    for (var fixture : fixtures) {
      long principal = fixture.get("principalMinor").asLong();
      var input = loan(principal, fixture.get("aprPercent").asText(), fixture.get("termMonths").asInt(), fixture.get("addonMinor").asLong());
      var result = calculator.calculate(input);
      assertThat(result.status()).as(fixture.get("name").asText()).isEqualTo("AVAILABLE");
      var output = json.valueToTree(result);
      for (String field : List.of("paymentMinor", "finalPaymentMinor", "totalScheduledPaymentsMinor", "totalInterestMinor"))
        assertThat(output.get(field).asLong()).as(field).isEqualTo(fixture.get(field).asLong());
      assertThat(result.totalTransactionCostMinor()).isEqualTo(result.totalScheduledPaymentsMinor() + 215000);
      assertThat(result.financedAddOns().getFirst().incrementalScheduledPaymentsMinor()).isEqualTo(fixture.get("addonScheduledMinor").asLong());
      assertThat(result.financedAddOns().getFirst().incrementalInterestMinor()).isEqualTo(fixture.get("addonInterestMinor").asLong());
      assertThat(result.quotedMinusCalculatedMinor()).isEqualTo(90000 - result.paymentMinor());
      assertThat(result.withinTolerance()).isFalse();
      assertThat(result.possibleCauses()).isNotEmpty();
      assertThat(calculator.calculate(input)).isEqualTo(result);
    }
  }
  @Test void seededPropertiesPreserveTotalsAndMonotonicInterest() throws Exception {
    var config = json.readTree(getClass().getResourceAsStream("/fixtures/financing/properties.json"));
    Random random = new Random(config.get("seed").asLong());
    for (int i = 0; i < config.get("samples").asInt(); i++) {
      long p = random.nextLong(config.get("minPrincipalMinor").asLong(), config.get("maxPrincipalMinor").asLong());
      int term = config.get("terms").get(random.nextInt(config.get("terms").size())).asInt();
      var zero = calculator.calculate(loan(p, "0", term, p / 10));
      var lower = calculator.calculate(loan(p, "4", term, p / 10));
      var higher = calculator.calculate(loan(p, "9", term, p / 10));
      assertThat(zero.totalInterestMinor()).isZero();
      assertThat(zero.totalScheduledPaymentsMinor()).isEqualTo(p);
      assertThat(lower.totalInterestMinor()).isGreaterThan(zero.totalInterestMinor());
      assertThat(higher.totalInterestMinor()).isGreaterThan(lower.totalInterestMinor());
      assertThat(higher.totalScheduledPaymentsMinor()).isEqualTo(p + higher.totalInterestMinor());
      assertThat(higher.totalScheduledPaymentsMinor()).isEqualTo(higher.paymentMinor() * (term - 1) + higher.finalPaymentMinor());
      assertThat(higher.financedAddOns().getFirst().incrementalInterestMinor()).isPositive();
    }
  }
  @Test void everyRequiredInputFailsClosedAndUnsupportedSchedulesHaveNoAmounts() {
    for (String field : List.of("currency", "purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor", "principalMinor",
        "aprPercent", "termMonths", "schedule", "paymentTiming", "financedFeesMinor", "upfrontFeesMinor",
        "cashPaidAtClosingMinor", "deferredChargesMinor", "financedAddOns")) {
      ObjectNode input = json.valueToTree(loan(3000000, "6", 60, 200000));
      input.putNull(field);
      var result = calculator.calculate(json.convertValue(input, FinanceLoan.class));
      assertThat(result.status()).as(field).isEqualTo("UNAVAILABLE");
      assertThat(result.paymentMinor()).isNull();
      assertThat(result.totalTransactionCostMinor()).isNull();
    }
    for (String schedule : List.of("BALLOON", "IRREGULAR", "VARIABLE_RATE")) {
      ObjectNode input = json.valueToTree(loan(3000000, "6", 60, 200000)); input.put("schedule", schedule);
      assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).status()).isEqualTo("UNSUPPORTED");
    }
    ObjectNode input = json.valueToTree(loan(3000000, "6", 60, 200000)); input.put("paymentTiming", "45_DAYS");
    assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).paymentMinor()).isNull();
  }
  @Test void extremeTermsAndInvalidValuesNeverInventEconomicsOrOverflow() {
    for (String apr : List.of("-1", "101", "0.0000000001"))
      assertThat(calculator.calculate(loan(3000000, apr, 60, 0)).status()).isEqualTo("UNAVAILABLE");
    assertThat(calculator.calculate(loan(100, "0", 1200, 0)).status()).isEqualTo("AVAILABLE");
    assertThat(calculator.calculate(loan(600, "0", 1200, 0)).status()).isEqualTo("UNAVAILABLE");
    // Long terms amplify cent rounding; fail closed instead of overflowing an integer.
    assertThat(calculator.calculate(loan(100000000000L, "100", 1200, 0)).status()).isEqualTo("UNAVAILABLE");
    assertThat(calculator.calculate(loan(1000, "6", 12, 1001)).status()).isEqualTo("UNAVAILABLE");
    assertThat(calculator.calculate(loan(1000, "0.00000001", 12, 0)).status()).isEqualTo("AVAILABLE");
  }
  @Test void feesAreNotDoubleCountedAndToleranceIsInclusive() {
    ObjectNode input = json.valueToTree(loan(3000000, "6", 60, 200000));
    input.put("financedFeesMinor", 20000); input.put("netAmountDueMinor", 2980000);
    var base = calculator.calculate(json.convertValue(input, FinanceLoan.class));
    assertThat(base.status()).isEqualTo("AVAILABLE");
    input.put("quotedPaymentMinor", base.paymentMinor() + 100);
    assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).withinTolerance()).isTrue();
    input.put("quotedPaymentMinor", base.paymentMinor() - 101);
    assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).withinTolerance()).isFalse();
    input.putNull("quotedPaymentMinor");
    assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).withinTolerance()).isNull();
    input.put("principalMinor", 2999999);
    assertThat(calculator.calculate(json.convertValue(input, FinanceLoan.class)).status()).isEqualTo("UNAVAILABLE");
  }
}
