package com.dealanalyzer.loan.preapproval;

import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public interface PreApprovalAdapter {
  Provider provider();
  /** Send only a soft-inquiry request under the recorded written authorization; never request a hard inquiry. */
  Result request(LoanApplication application, CreditAuthorization authorization);

  /** Code-owned metadata, never copied from the partner's response. */
  record Provider(String name, String version, String licenceMetadata, boolean synthetic) {
    public Provider {
      if (name == null || name.isBlank() || name.length() > 64 || version == null || version.isBlank()
          || version.length() > 100 || licenceMetadata == null || licenceMetadata.isBlank()
          || licenceMetadata.length() > 100) throw new IllegalArgumentException("Loan provider metadata required");
    }
  }
  enum Availability { AVAILABLE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  enum Reason { PROVIDER_UNAVAILABLE, TIMED_OUT, RATE_LIMITED, INVALID_RESPONSE, NON_SOFT_INQUIRY, EXPIRED }
  enum InquiryType { SOFT, HARD, UNKNOWN }
  record Conditions(int maximumVehicleAgeYears, long maximumMileage, long minimumDownPaymentMinor) {
    public Conditions {
      if (maximumVehicleAgeYears < 0 || maximumMileage < 0 || minimumDownPaymentMinor < 0)
        throw new IllegalArgumentException("Invalid loan conditions");
    }
  }
  record Source(String reference, Instant observedAt) {
    public Source {
      if (reference == null || reference.isBlank() || reference.length() > 200 || observedAt == null)
        throw new IllegalArgumentException("Loan source required");
    }
  }
  record PreApproval(long amountMinor, String currency, BigDecimal aprPercent, int termMonths,
      long upfrontFeesMinor, Long lenderPaymentMinor, Conditions conditions, Instant expiresAt,
      InquiryType inquiryType, URI continueUrl, Source source) {
    public PreApproval {
      if (amountMinor <= 0 || amountMinor > 100_000_000_000L || !"USD".equals(currency)
          || aprPercent == null || aprPercent.signum() < 0 || aprPercent.compareTo(new BigDecimal("100")) > 0
          || aprPercent.stripTrailingZeros().scale() > 8 || termMonths < 1 || termMonths > 1200
          || upfrontFeesMinor < 0 || upfrontFeesMinor > 100_000_000_000L
          || (lenderPaymentMinor != null && (lenderPaymentMinor < 0 || lenderPaymentMinor > 100_000_000_000L))
          || conditions == null || expiresAt == null || inquiryType == null || source == null
          || continueUrl == null || !"https".equalsIgnoreCase(continueUrl.getScheme())
          || continueUrl.getHost() == null || continueUrl.getUserInfo() != null)
        throw new IllegalArgumentException("Invalid pre-approval");
    }
    @Override public String toString() { return "PreApproval[REDACTED]"; }
  }
  record LenderEntry(String lenderName, PartnerOutcome outcome, PreApproval preApproval) {
    public LenderEntry {
      if (lenderName == null || lenderName.isBlank() || lenderName.length() > 100 || outcome == null
          || (outcome == PartnerOutcome.OFFERED) != (preApproval != null))
        throw new IllegalArgumentException("Invalid lender outcome");
    }
  }
  record Result(Availability availability, Reason reason, Provider provider, Instant retrievedAt,
      BigDecimal costUsd, List<LenderEntry> lenders) {
    public Result {
      if (availability == null || provider == null || retrievedAt == null || lenders == null
          || (costUsd != null && costUsd.signum() < 0)
          || (availability == Availability.AVAILABLE && reason != null)
          || (availability != Availability.AVAILABLE && (reason == null || !lenders.isEmpty()))
          || (availability == Availability.TIMED_OUT && reason != Reason.TIMED_OUT)
          || (availability == Availability.RATE_LIMITED && reason != Reason.RATE_LIMITED))
        throw new IllegalArgumentException("Invalid pre-approval result");
      lenders = List.copyOf(lenders);
    }
    public String notice() {
      return availability == Availability.AVAILABLE
          ? provider.synthetic() ? "Synthetic pre-approvals for development only." : null
          : "Pre-approvals unavailable: " + reason.name() + ". No offer was substituted.";
    }
  }
}
