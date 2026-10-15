package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dealanalyzer.loan.preapproval.PreApprovalAdapter.*;
import com.dealanalyzer.loan.preapproval.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreApprovalProviderMeterTest {
  private final Provider provider = new Provider("LICENSED_TEST", "v1", "TEST_TERMS", false);
  private final LoanApplication application = new LoanApplication(2500000, 60,
      new LoanApplication.Vehicle(2024, "Honda", "Civic", 15000), 8000000, LoanApplication.Housing.RENT, 150000);
  private ProviderUsageStore store() {
    var store = mock(ProviderUsageStore.class);
    when(store.startPreApproval("case", null, null, provider)).thenReturn("usage");
    when(store.startPreApproval(null, "account", "journey", provider)).thenReturn("journey-usage");
    return store;
  }
  private Result available(InquiryType inquiry) {
    var now = Instant.now();
    var offer = new PreApproval(2500000, "USD", new BigDecimal("6.25"), 60, 0, null,
        new Conditions(10, 100000, 0), now.plusSeconds(3600), inquiry,
        URI.create("https://lender.invalid/secret-link"), new Source("secret-reference", now));
    return new Result(Availability.AVAILABLE, null, provider, now, new BigDecimal("0.025"),
        List.of(new LenderEntry("Test lender", PartnerOutcome.OFFERED, offer)));
  }
  @Test void everyAvailabilityRecordsCostStatusAndLatency() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      for (var availability : Availability.values()) {
        var reason = availability == Availability.TIMED_OUT ? Reason.TIMED_OUT
            : availability == Availability.RATE_LIMITED ? Reason.RATE_LIMITED : Reason.PROVIDER_UNAVAILABLE;
        var response = availability == Availability.AVAILABLE ? available(InquiryType.SOFT)
            : PreApprovalContract.unavailable(provider, reason, Instant.now(), BigDecimal.ZERO);
        assertThat(meter.callPreApproval("case", null, null, provider, application, () -> response)).isEqualTo(response);
        verify(store).preApprovalResult("usage", response);
      }
      verify(store).finish("usage", ProviderMeter.Status.SUCCESS);
      verify(store, times(3)).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
      verify(store, times(4)).preApprovalLatency(eq("usage"), longThat(ms -> ms >= 0));
    } finally { meter.close(); }
  }
  @Test void journeyWithoutCaseUsesTheSameBoundary() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var response = available(InquiryType.SOFT);
      assertThat(meter.callPreApproval(null, "account", "journey", provider, application, () -> response)).isEqualTo(response);
      verify(store).startPreApproval(null, "account", "journey", provider);
      verify(store).finish("journey-usage", ProviderMeter.Status.SUCCESS);
      verify(store).preApprovalLatency(eq("journey-usage"), anyLong());
    } finally { meter.close(); }
  }
  @Test void nonSoftResultIsDroppedAndBilledCostRetained() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      for (var inquiry : List.of(InquiryType.HARD, InquiryType.UNKNOWN)) {
        var result = meter.callPreApproval("case", null, null, provider, application, () -> available(inquiry));
        assertThat(result.reason()).isEqualTo(Reason.NON_SOFT_INQUIRY);
        assertThat(result.lenders()).isEmpty();
        assertThat(result.costUsd()).isEqualByComparingTo("0.025");
      }
      verify(store, times(2)).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }
  @Test void timeoutNullAndExceptionNeverSupplyOffersOrExposeRemoteText() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofMillis(50), 1);
    try {
      var timeout = meter.callPreApproval("case", null, null, provider, application, () -> {
        try { Thread.sleep(5000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return null;
      });
      assertThat(timeout.reason()).isEqualTo(Reason.TIMED_OUT);
      assertThat(timeout.lenders()).isEmpty(); assertThat(timeout.costUsd()).isNull();
      var missing = meter.callPreApproval("case", null, null, provider, application, () -> null);
      assertThat(missing.reason()).isEqualTo(Reason.INVALID_RESPONSE);
      var failed = meter.callPreApproval("case", null, null, provider, application,
          () -> { throw new IllegalStateException("secret upstream financing details"); });
      assertThat(failed.notice()).doesNotContain("secret"); assertThat(failed.lenders()).isEmpty();
      verify(store).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_FAILURE);
    } finally { meter.close(); }
  }
  @Test void closedPoolDoesNotClaimATimeoutOrSend() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1); meter.close();
    var invoked = new java.util.concurrent.atomic.AtomicBoolean();
    var result = meter.callPreApproval("case", null, null, provider, application, () -> { invoked.set(true); return null; });
    assertThat(invoked).isFalse(); assertThat(result.reason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
  }
}
