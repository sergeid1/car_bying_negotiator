package com.dealanalyzer.finance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Full-term estimates only; purchase differences must be resolved by the Buyer. */
public final class FinancingComparison {
  public static final String VERSION = "outside-financing-comparison.v1";
  public Result compare(FinanceLoan dealer, FinanceLoan outside) {
    var calculator = new FinancingCalculator();
    var dealerResult = calculator.calculate(dealer);
    var outsideResult = calculator.calculate(outside);
    var issues = new ArrayList<String>();
    dealerResult.issues().forEach(issue -> issues.add("Dealer: " + issue));
    outsideResult.issues().forEach(issue -> issues.add("Outside: " + issue));
    var differences = new ArrayList<String>();
    if (dealer != null && outside != null) {
      align(differences, "Purchase price after discounts and rebates", dealer.purchasePriceMinor(), outside.purchasePriceMinor());
      align(differences, "Out-the-door total", dealer.outTheDoorTotalMinor(), outside.outTheDoorTotalMinor());
      align(differences, "Net amount due", dealer.netAmountDueMinor(), outside.netAmountDueMinor());
      if (dealer.cashPaidAtClosingMinor() != null && dealer.upfrontFeesMinor() != null
          && outside.cashPaidAtClosingMinor() != null && outside.upfrontFeesMinor() != null)
        align(differences, "Cash contribution excluding lender fees", dealer.cashPaidAtClosingMinor() - dealer.upfrontFeesMinor(),
            outside.cashPaidAtClosingMinor() - outside.upfrontFeesMinor());
      align(differences, "Deferred charges", dealer.deferredChargesMinor(), outside.deferredChargesMinor());
      align(differences, "Financed add-ons", dealer.financedAddOns(), outside.financedAddOns());
    }
    boolean supported = "AVAILABLE".equals(dealerResult.status()) && "AVAILABLE".equals(outsideResult.status());
    boolean comparable = supported && differences.isEmpty();
    Long aprDelta = null;
    if (comparable) {
      var aprOnly = calculator.calculate(new FinanceLoan(dealer.currency(), dealer.purchasePriceMinor(),
          dealer.outTheDoorTotalMinor(), dealer.netAmountDueMinor(), dealer.principalMinor(), outside.aprPercent(),
          dealer.termMonths(), dealer.schedule(), dealer.paymentTiming(), dealer.financedFeesMinor(),
          dealer.upfrontFeesMinor(), dealer.cashPaidAtClosingMinor(), dealer.deferredChargesMinor(), null, dealer.financedAddOns()));
      if ("AVAILABLE".equals(aprOnly.status()))
        aprDelta = aprOnly.totalScheduledPaymentsMinor() - dealerResult.totalScheduledPaymentsMinor();
      else issues.add("APR-only counterfactual is outside supported calculation bounds.");
    }
    return new Result(VERSION, comparable ? "COMPARABLE" : supported ? "NOT_COMPARABLE" : "UNAVAILABLE",
        List.copyOf(issues), List.copyOf(differences), dealerResult, outsideResult,
        comparable ? outsideResult.totalTransactionCostMinor() - dealerResult.totalTransactionCostMinor() : null, aprDelta);
  }
  private void align(List<String> differences, String label, Object dealer, Object outside) {
    if (!Objects.equals(dealer, outside)) differences.add(label + " differs. Confirm matching purchase assumptions before comparing costs.");
  }
  public record Result(String policyVersion, String status, List<String> issues, List<String> purchaseDifferences,
      FinancingCalculator.Result dealer, FinancingCalculator.Result outside,
      Long outsideMinusDealerCostMinor, Long aprOnlyScheduledPaymentsDeltaMinor) {}
}
