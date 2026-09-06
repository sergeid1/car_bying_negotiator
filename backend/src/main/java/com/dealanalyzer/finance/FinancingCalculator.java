package com.dealanalyzer.finance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Pure ordinary-annuity calculation, independent of persistence and clocks. */
public final class FinancingCalculator {
  public static final String VERSION = "dealer-financing.v1";
  public static final String TIMING = "MONTHLY_IN_ARREARS_FIRST_PAYMENT_ONE_MONTH";
  public static final String ROUNDING = "DECIMAL128 intermediates; HALF_UP cents for regular payments; remaining balance plus interest rounded for final payment";
  private static final MathContext MC = MathContext.DECIMAL128;
  private static final long MAX_MONEY = 100_000_000_000L;
  public Result calculate(FinanceLoan loan) {
    List<String> issues = new ArrayList<>();
    if (loan == null) return unavailable("UNAVAILABLE", List.of("Verified Finance Offer is missing."));
    if (loan.schedule() != null && !"STANDARD_FIXED_RATE".equals(loan.schedule()))
      return unavailable("UNSUPPORTED", List.of("Balloon, variable-rate and irregular schedules are unsupported."));
    if (loan.paymentTiming() != null && !TIMING.equals(loan.paymentTiming()))
      return unavailable("UNSUPPORTED", List.of("Only monthly payments starting one month after funding are supported."));
    if (!"USD".equals(loan.currency())) issues.add("USD currency is required.");
    Long[] required = {loan.purchasePriceMinor(), loan.outTheDoorTotalMinor(), loan.netAmountDueMinor(),
        loan.principalMinor(), loan.financedFeesMinor(), loan.upfrontFeesMinor(),
        loan.cashPaidAtClosingMinor(), loan.deferredChargesMinor()};
    for (Long money : required) if (money == null || money < 0 || money > MAX_MONEY) {
      issues.add("Purchase amounts, principal, all fees, cash paid and deferred charges must be explicitly known and within supported bounds (including explicit zero)."); break;
    }
    if (loan.aprPercent() == null || loan.aprPercent().stripTrailingZeros().scale() > 8 || loan.aprPercent().signum() < 0 || loan.aprPercent().compareTo(new BigDecimal("100")) > 0)
      issues.add("APR must be known and between 0 and 100 percent with at most eight decimal places.");
    if (loan.termMonths() == null || loan.termMonths() < 1 || loan.termMonths() > 1200)
      issues.add("Term must be known and between 1 and 1200 months.");
    if (loan.schedule() == null || loan.paymentTiming() == null) issues.add("Schedule and first-payment timing are required.");
    if (loan.quotedPaymentMinor() != null && (loan.quotedPaymentMinor() < 0 || loan.quotedPaymentMinor() > MAX_MONEY))
      issues.add("Quoted payment is outside supported bounds.");
    if (loan.financedAddOns() == null) issues.add("Confirm the financed add-on list, including an empty list when none apply.");
    else {
      BigDecimal sum = BigDecimal.ZERO;
      for (var addOn : loan.financedAddOns()) {
        if (addOn.label() == null || addOn.label().isBlank() || addOn.cashPriceMinor() == null
            || addOn.cashPriceMinor() < 0 || addOn.cashPriceMinor() > MAX_MONEY)
          issues.add("Each financed add-on needs a label and nonnegative cash price within supported bounds.");
        else sum = sum.add(BigDecimal.valueOf(addOn.cashPriceMinor()));
      }
      if (loan.principalMinor() != null && sum.compareTo(BigDecimal.valueOf(loan.principalMinor())) > 0)
        issues.add("Financed add-on prices cannot exceed principal.");
    }
    if (!issues.isEmpty()) return unavailable("UNAVAILABLE", issues);
    if (loan.principalMinor() != loan.netAmountDueMinor() + loan.financedFeesMinor())
      issues.add("Principal must equal net amount due plus financed lender fees.");
    if (loan.cashPaidAtClosingMinor() < loan.upfrontFeesMinor())
      issues.add("Cash paid at closing must include the upfront lender fees.");
    if (!issues.isEmpty()) return unavailable("UNAVAILABLE", issues);
    BigDecimal rate = loan.aprPercent().divide(new BigDecimal("1200"), MC);
    Schedule full = schedule(loan.principalMinor(), rate, loan.termMonths());
    if (full == null) return unavailable("UNAVAILABLE", List.of("The final payment is outside supported rounding or monetary bounds for this principal and term."));
    List<AddOnEffect> effects = new ArrayList<>();
    for (var addOn : loan.financedAddOns()) {
      Schedule without = schedule(loan.principalMinor() - addOn.cashPriceMinor(), rate, loan.termMonths());
      if (without == null) return unavailable("UNAVAILABLE", List.of("Add-on counterfactual is outside supported rounding bounds."));
      long delta = full.total - without.total;
      effects.add(new AddOnEffect(addOn.label(), addOn.cashPriceMinor(), full.payment - without.payment,
          delta, delta - addOn.cashPriceMinor()));
    }
    Long difference = loan.quotedPaymentMinor() == null ? null : loan.quotedPaymentMinor() - full.payment;
    return new Result("AVAILABLE", VERSION, TIMING, ROUNDING, issues, full.payment, full.finalPayment,
        full.total, full.total - loan.principalMinor(), loan.cashPaidAtClosingMinor() + full.total + loan.deferredChargesMinor(),
        loan.quotedPaymentMinor(), difference, 100L, difference == null ? null : Math.abs(difference) <= 100,
        difference != null && Math.abs(difference) > 100
            ? List.of("Possible causes include rounding, first-payment timing, fees, principal, or different loan terms; ask the dealer to reconcile.") : List.of(), effects);
  }

  private Schedule schedule(long principal, BigDecimal rate, int months) {
    try {
      BigDecimal p = BigDecimal.valueOf(principal);
      BigDecimal factor = BigDecimal.ONE.add(rate, MC);
      BigDecimal payment = rate.signum() == 0 ? p.divide(BigDecimal.valueOf(months), MC)
          : p.multiply(rate, MC).divide(BigDecimal.ONE.subtract(BigDecimal.ONE.divide(factor.pow(months, MC), MC), MC), MC);
      long regular = payment.setScale(0, RoundingMode.HALF_UP).longValueExact();
      BigDecimal balance = p;
      for (int i = 1; i < months; i++) balance = balance.multiply(factor, MC).subtract(BigDecimal.valueOf(regular), MC);
      long last = balance.multiply(factor, MC).setScale(0, RoundingMode.HALF_UP).longValueExact();
      if (last < 0 || last > MAX_MONEY) return null;
      return new Schedule(regular, last, Math.addExact(Math.multiplyExact(regular, months - 1L), last));
    } catch (ArithmeticException outsideSupportedPrecision) { return null; }
  }
  private Result unavailable(String status, List<String> issues) {
    return new Result(status, VERSION, TIMING, ROUNDING, List.copyOf(issues), null, null, null, null, null,
        null, null, 100L, null, List.of(), List.of());
  }
  private record Schedule(long payment, long finalPayment, long total) {}
  public record AddOnEffect(String label, long cashPriceMinor, long incrementalPaymentMinor,
      long incrementalScheduledPaymentsMinor, long incrementalInterestMinor) {}
  public record Result(String status, String policyVersion, String timing, String rounding,
      List<String> issues, Long paymentMinor, Long finalPaymentMinor, Long totalScheduledPaymentsMinor,
      Long totalInterestMinor, Long totalTransactionCostMinor, Long quotedPaymentMinor,
      Long quotedMinusCalculatedMinor, long toleranceMinor, Boolean withinTolerance,
      List<String> possibleCauses, List<AddOnEffect> financedAddOns) {}
}
