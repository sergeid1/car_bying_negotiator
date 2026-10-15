package com.dealanalyzer.loan.preapproval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreApprovalContractTest {
  private final Instant now = Instant.parse("2026-10-15T12:00:00Z");
  private final Provider provider = new Provider("TEST", "v1", "TEST_TERMS", false);
  private final LoanApplication application = new LoanApplication(2500000, 60,
      new LoanApplication.Vehicle(2024, "Honda", "Civic", 15000), 8000000, LoanApplication.Housing.RENT, 150000);
  private PreApproval offer(BigDecimal apr, int months, long fee, InquiryType inquiry) {
    return new PreApproval(2500000, "USD", apr, months, fee, 12345L, new Conditions(10, 100000, 0),
        now.plusSeconds(1000), inquiry, URI.create("https://lender.invalid/continue"), new Source("fixture", now));
  }
  private LenderEntry entry(String name, BigDecimal apr, int months, long fee) {
    return new LenderEntry(name, PartnerOutcome.OFFERED, offer(apr, months, fee, InquiryType.SOFT));
  }
  private Result result(List<LenderEntry> entries) { return new Result(Availability.AVAILABLE, null, provider, now, BigDecimal.ZERO, entries); }
  @Test void totalCostOrderingIncludesTermAndFeesAndIgnoresStatedPayment() {
    var entries = List.of(entry("Low APR long term", new BigDecimal("5"), 84, 0),
        entry("Higher APR short term", new BigDecimal("6"), 36, 0), entry("Zero APR with fees", BigDecimal.ZERO, 60, 900000));
    var ordered = PreApprovalContract.orderedOffers(result(entries), application, now);
    assertThat(ordered).extracting(PreApprovalContract.CalculatedOffer::lenderName)
        .containsExactly("Higher APR short term", "Low APR long term", "Zero APR with fees");
    for (var calculated : ordered) {
      var offer = calculated.preApproval();
      var expected = new FinancingCalculator().partialSchedule(offer.amountMinor(), offer.aprPercent(), offer.termMonths());
      assertThat(calculated.paymentMinor()).isEqualTo(expected.paymentMinor());
      assertThat(calculated.totalInterestMinor()).isEqualTo(expected.interestMinor());
      assertThat(calculated.totalCostMinor()).isEqualTo(expected.totalPaymentsMinor() + offer.upfrontFeesMinor());
    }
  }
  @Test void hardAndUnknownInquiriesCannotReachCalculatedOffers() {
    for (var inquiry : List.of(InquiryType.HARD, InquiryType.UNKNOWN)) {
      var result = result(List.of(new LenderEntry("Test", PartnerOutcome.OFFERED, offer(BigDecimal.ZERO, 60, 0, inquiry))));
      assertThat(PreApprovalContract.failure(result, provider, application, now)).isEqualTo(Reason.NON_SOFT_INQUIRY);
      assertThatIllegalArgumentException().isThrownBy(() -> PreApprovalContract.orderedOffers(result, application, now));
    }
  }
  @Test void serviceRefusesMissingOrFutureAuthorizationWithoutMeteringOrSending() {
    var adapter = mock(PreApprovalAdapter.class); var meter = mock(ProviderMeter.class);
    var service = new PreApprovalService(adapter, meter);
    assertThatIllegalArgumentException().isThrownBy(() -> service.request("case", null, null, application, null));
    assertThatIllegalArgumentException().isThrownBy(() -> service.request(null, "account", "journey", application,
        new CreditAuthorization("v1", Instant.now().plusSeconds(60))));
    verifyNoInteractions(adapter, meter);
  }
  @Test void wrongProviderMismatchedAmountFutureSourceAndExpiredEvidenceAreRejected() {
    var valid = result(List.of(entry("Test", BigDecimal.ZERO, 60, 0)));
    assertThat(PreApprovalContract.failure(valid, new Provider("OTHER", "v1", "TERMS", false), application, now)).isEqualTo(Reason.INVALID_RESPONSE);
    var different = new LoanApplication(2600000, 60, application.vehicle(), 0, LoanApplication.Housing.OTHER, 0);
    assertThat(PreApprovalContract.failure(valid, provider, different, now)).isEqualTo(Reason.INVALID_RESPONSE);
    assertThat(PreApprovalContract.failure(valid, provider, application, now.plusSeconds(1000))).isEqualTo(Reason.EXPIRED);
    assertThat(PreApprovalContract.failure(valid, provider, application, now.minusSeconds(120))).isEqualTo(Reason.INVALID_RESPONSE);
  }
  @Test void invalidOutcomeAndUnsafeUrlCannotBeConstructed() {
    assertThatIllegalArgumentException().isThrownBy(() -> new LenderEntry("Test", PartnerOutcome.OFFERED, null));
    assertThatIllegalArgumentException().isThrownBy(() -> new LenderEntry("Test", PartnerOutcome.DECLINED, offer(BigDecimal.ZERO, 60, 0, InquiryType.SOFT)));
    assertThatIllegalArgumentException().isThrownBy(() -> new PreApproval(2500000, "USD", BigDecimal.ZERO, 60, 0, null,
        new Conditions(10, 100000, 0), now.plusSeconds(1000), InquiryType.SOFT,
        URI.create("http://lender.invalid/continue"), new Source("fixture", now)));
    assertThat(application.toString()).isEqualTo("LoanApplication[REDACTED]");
  }
}
