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

  /** Same requested principal; full scheduled payments plus explicit upfront fees, no compensation input. */
  public static List<CalculatedOffer> orderedOffers(Result result, LoanApplication application, Instant now) {
    if (failure(result, result.provider(), application, now) != null)
      throw new IllegalArgumentException("Invalid pre-approval contract");
    var calculator = new FinancingCalculator();
    return result.lenders().stream().filter(entry -> entry.preApproval() != null).map(entry -> {
      var offer = entry.preApproval();
      var schedule = calculator.partialSchedule(offer.amountMinor(), offer.aprPercent(), offer.termMonths());
      var reconciliation = calculator.reconcilePayment(offer.amountMinor(), offer.aprPercent(), offer.termMonths(), offer.lenderPaymentMinor());
      return new CalculatedOffer(entry.lenderName(), offer, schedule.paymentMinor(), schedule.interestMinor(),
          Math.addExact(schedule.totalPaymentsMinor(), offer.upfrontFeesMinor()),
          offer.lenderPaymentMinor() == null ? null : offer.lenderPaymentMinor() - schedule.paymentMinor(), reconciliation);
    }).sorted(Comparator.comparingLong(CalculatedOffer::totalCostMinor).thenComparing(CalculatedOffer::lenderName)).toList();
  }
}
