package com.dealanalyzer.finance;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancingComparisonTest {
  private FinanceLoan loan(String apr, long fee) {
    return new FinanceLoan("USD", 3000000L, 3000000L, 3000000L, 3000000L + fee,
        new BigDecimal(apr), 60, "STANDARD_FIXED_RATE", FinancingCalculator.TIMING,
        fee, 0L, 0L, 0L, null, List.of(new FinanceLoan.AddOn("Protection", 200000L)));
  }
  @Test void alignedOffersShowFullTermCostsAndIsolateAprFromFees() {
    var result = new FinancingComparison().compare(loan("6", 0), loan("0", 10000));
    assertThat(result.status()).isEqualTo("COMPARABLE");
    assertThat(result.dealer().paymentMinor()).isEqualTo(57998);
    assertThat(result.dealer().totalScheduledPaymentsMinor()).isEqualTo(3479908);
    assertThat(result.outside().totalTransactionCostMinor()).isEqualTo(3010000);
    assertThat(result.outsideMinusDealerCostMinor()).isEqualTo(-469908);
    assertThat(result.aprOnlyScheduledPaymentsDeltaMinor()).isEqualTo(-479908);
    assertThat(result.dealer().financedAddOns().getFirst().incrementalInterestMinor()).isPositive();
    assertThat(result.outside().financedAddOns().getFirst().incrementalInterestMinor()).isZero();
    assertThat(new FinancingComparison().compare(loan("6", 0), loan("0", 10000))).isEqualTo(result);
  }
  @Test void purchaseDifferencesAndUnknownOrUnsupportedTermsNeverProduceAWinner() {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var dealer = loan("6", 0);
    for (String field : List.of("purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor",
        "cashPaidAtClosingMinor", "deferredChargesMinor", "financedAddOns")) {
      var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(dealer);
      if (field.equals("financedAddOns")) input.putArray(field);
      else input.put(field, input.get(field).asLong() + 10000);
      if (field.equals("netAmountDueMinor")) input.put("principalMinor", 3010000);
      var result = new FinancingComparison().compare(dealer, json.convertValue(input, FinanceLoan.class));
      assertThat(result.status()).as(field).isEqualTo("NOT_COMPARABLE");
      assertThat(result.purchaseDifferences()).isNotEmpty();
      assertThat(result.outsideMinusDealerCostMinor()).isNull();
      assertThat(result.aprOnlyScheduledPaymentsDeltaMinor()).isNull();
    }
    for (String field : List.of("principalMinor", "aprPercent", "termMonths", "paymentTiming", "financedFeesMinor")) {
      var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(dealer);
      input.putNull(field);
      var result = new FinancingComparison().compare(dealer, json.convertValue(input, FinanceLoan.class));
      assertThat(result.status()).isEqualTo("UNAVAILABLE");
      assertThat(result.issues()).isNotEmpty();
      assertThat(result.outsideMinusDealerCostMinor()).isNull();
    }
    var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(dealer);
    input.put("paymentTiming", "45_DAYS");
    assertThat(new FinancingComparison().compare(dealer, json.convertValue(input, FinanceLoan.class)).outsideMinusDealerCostMinor()).isNull();
  }
}
