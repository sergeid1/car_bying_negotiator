package com.dealanalyzer.loan.preapproval;

import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import org.springframework.stereotype.Component;
import static com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;

/** Local fixtures only: never performs a credit inquiry or contacts a lender. */
@Component
public class DeterministicFakePreApprovalAdapter implements PreApprovalAdapter, DevelopmentEvidenceAdapter {
  public enum Scenario { NORMAL, ZERO_APR, PAYMENT_MISMATCH, MIXED_OUTCOMES, HARD_INQUIRY, EXPIRED, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  private final Clock clock;
  private final Scenario scenario;
  public DeterministicFakePreApprovalAdapter() { this(Clock.systemUTC(), Scenario.NORMAL); }
  public DeterministicFakePreApprovalAdapter(Clock clock, Scenario scenario) { this.clock = clock; this.scenario = scenario; }
  @Override public Provider provider() {
    return new Provider("SYNTHETIC_PRE_APPROVAL", "deterministic-pre-approval.v1", "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED", true);
  }
  @Override public Result request(LoanApplication application, CreditAuthorization authorization) {
    CreditAuthorization.require(authorization, clock.instant());
    if (application == null) throw new IllegalArgumentException("Loan application required");
    var now = clock.instant();
    Reason reason = switch (scenario) {
      case UNAVAILABLE -> Reason.PROVIDER_UNAVAILABLE;
      case TIMED_OUT -> Reason.TIMED_OUT;
      case RATE_LIMITED -> Reason.RATE_LIMITED;
      default -> null;
    };
    if (reason != null) return PreApprovalContract.unavailable(provider(), reason, now, BigDecimal.ZERO);
    var offer = new PreApproval(application.amountMinor(), "USD", new BigDecimal(scenario == Scenario.ZERO_APR ? "0" : "6.25"),
        application.termMonths(), 0, scenario == Scenario.PAYMENT_MISMATCH ? 999999L : null,
        new Conditions(12, 150000, 100000), scenario == Scenario.EXPIRED ? now.minusSeconds(1) : now.plusSeconds(86400),
        scenario == Scenario.HARD_INQUIRY ? InquiryType.HARD : InquiryType.SOFT,
        URI.create("https://lender.invalid/continue"), new Source("synthetic-loan-1", now));
    var entries = new java.util.ArrayList<LenderEntry>();
    entries.add(new LenderEntry("Synthetic lender", PartnerOutcome.OFFERED, offer));
    if (scenario == Scenario.MIXED_OUTCOMES)
      for (var outcome : PartnerOutcome.values()) if (outcome != PartnerOutcome.OFFERED)
        entries.add(new LenderEntry("Synthetic " + outcome.name(), outcome, null));
    var result = new Result(Availability.AVAILABLE, null, provider(), now, BigDecimal.ZERO, entries);
    var failure = PreApprovalContract.failure(result, provider(), application, now);
    return failure == null ? result : PreApprovalContract.unavailable(provider(), failure, now, BigDecimal.ZERO);
  }
}
