package com.dealanalyzer.loan.preapproval;

import com.dealanalyzer.finance.FinancingCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import static com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;

public final class PreApprovalContract {
  private PreApprovalContract() {}
  public static Reason failure(Result result, Provider provider, LoanApplication application, Instant now) {
    if (result == null || !provider.equals(result.provider()) || result.retrievedAt().isAfter(now.plusSeconds(60)))
      return Reason.INVALID_RESPONSE;
    for (var lender : result.lenders()) {
      var offer = lender.preApproval();
      if (offer == null) continue;
      if (offer.inquiryType() != InquiryType.SOFT) return Reason.NON_SOFT_INQUIRY;
      if (!offer.expiresAt().isAfter(now)) return Reason.EXPIRED;
      if (offer.amountMinor() != application.amountMinor() || offer.source().observedAt().isAfter(result.retrievedAt().plusSeconds(60))
          || !offer.expiresAt().isAfter(offer.source().observedAt())
          || new FinancingCalculator().partialSchedule(offer.amountMinor(), offer.aprPercent(), offer.termMonths()) == null)
        return Reason.INVALID_RESPONSE;
    }
    return null;
  }
  public static Result unavailable(Provider provider, Reason reason, Instant now, BigDecimal cost) {
    return new Result(reason == Reason.TIMED_OUT ? Availability.TIMED_OUT
        : reason == Reason.RATE_LIMITED ? Availability.RATE_LIMITED : Availability.UNAVAILABLE,
        reason, provider, now, cost, List.of());
  }
  public record CalculatedOffer(String lenderName, PreApproval preApproval,
      long paymentMinor, long totalInterestMinor, long totalCostMinor,
      Long lenderMinusCalculatedMinor, FinancingCalculator.PaymentReconciliation reconciliation) {}

  /** FIN-01 figures for one offer: full scheduled payments plus explicit upfront fees; null outside the supported schedule. */
  public record Calculation(long paymentMinor, long totalInterestMinor, long totalCostMinor,
      Long lenderMinusCalculatedMinor, FinancingCalculator.PaymentReconciliation reconciliation) {}
  public static Calculation calculate(long amountMinor, BigDecimal aprPercent, int termMonths, long upfrontFeesMinor,
      Long lenderPaymentMinor) {
    var calculator = new FinancingCalculator();
    var schedule = calculator.partialSchedule(amountMinor, aprPercent, termMonths);
    if (schedule == null) return null;
    return new Calculation(schedule.paymentMinor(), schedule.interestMinor(), Math.addExact(schedule.totalPaymentsMinor(), upfrontFeesMinor),
        lenderPaymentMinor == null ? null : lenderPaymentMinor - schedule.paymentMinor(),
        calculator.reconcilePayment(amountMinor, aprPercent, termMonths, lenderPaymentMinor));
  }
  /** Total cost, then lender name: the only order, for partner and Buyer-entered offers alike (LOAN-02, PAY-02). */
  public static <T> Comparator<T> byTotalCost(java.util.function.ToLongFunction<T> totalCost, java.util.function.Function<T, String> lender) {
    return Comparator.comparingLong(totalCost).thenComparing(lender);
  }

  /** Same requested principal; full scheduled payments plus explicit upfront fees, no compensation input. */
  public static List<CalculatedOffer> orderedOffers(Result result, LoanApplication application, Instant now) {
    if (failure(result, result.provider(), application, now) != null)
      throw new IllegalArgumentException("Invalid pre-approval contract");
    return result.lenders().stream().filter(entry -> entry.preApproval() != null).map(entry -> {
      var offer = entry.preApproval();
      var calculation = calculate(offer.amountMinor(), offer.aprPercent(), offer.termMonths(), offer.upfrontFeesMinor(),
          offer.lenderPaymentMinor());
      return new CalculatedOffer(entry.lenderName(), offer, calculation.paymentMinor(), calculation.totalInterestMinor(),
          calculation.totalCostMinor(), calculation.lenderMinusCalculatedMinor(), calculation.reconciliation());
    }).sorted(byTotalCost(CalculatedOffer::totalCostMinor, CalculatedOffer::lenderName)).toList();
  }
}
