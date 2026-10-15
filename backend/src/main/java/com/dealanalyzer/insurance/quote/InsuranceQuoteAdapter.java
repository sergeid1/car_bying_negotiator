package com.dealanalyzer.insurance.quote;

import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * A licensed insurance comparison partner. The application goes only to the partner and the insurers quoting
 * through it, and only with the Buyer's consent (INS-02); no vendor payload crosses this seam.
 */
public interface InsuranceQuoteAdapter {
  Provider provider();
  Result request(InsuranceApplication application, InsuranceQuoteConsent consent);

  /** Code-owned partner identity and licence terms, never taken from a remote payload. */
  record Provider(String name, String version, String licenceMetadata, boolean synthetic) {
    public Provider {
      if (name == null || name.isBlank() || name.length() > 64 || version == null || version.isBlank()
          || version.length() > 100 || licenceMetadata == null || licenceMetadata.isBlank()
          || licenceMetadata.length() > 100) throw new IllegalArgumentException("Insurance partner metadata required");
    }
  }
  enum Availability { AVAILABLE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  enum Reason { PROVIDER_UNAVAILABLE, TIMED_OUT, RATE_LIMITED, STALE_RESPONSE, INVALID_RESPONSE }

  /**
   * One insurer's priced offer. {@code coverage} is what the insurer actually priced, which may differ from
   * the Buyer's choice; {@code continueUrl} is where the Buyer continues on the provider's own site.
   */
  record InsuranceQuote(long premiumMinor, String currency, Term term, CoverageSet coverage, Instant quotedAt,
      Instant expiresAt, URI continueUrl) {
    public InsuranceQuote {
      currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
      if (premiumMinor <= 0 || !"USD".equals(currency) || term == null || coverage == null || quotedAt == null
          || expiresAt == null || !expiresAt.isAfter(quotedAt) || continueUrl == null
          || !"https".equalsIgnoreCase(continueUrl.getScheme()) || continueUrl.getHost() == null
          || continueUrl.getUserInfo() != null)
        throw new IllegalArgumentException("Invalid insurance quote");
    }
    public long yearlyPremiumMinor() { return term.yearlyMinor(premiumMinor); }
  }

  /** What one insurer behind the partner returned; only an offer carries a quote. */
  record InsurerEntry(String insurer, PartnerOutcome outcome, InsuranceQuote quote) {
    public InsurerEntry {
      insurer = insurer == null ? null : insurer.strip();
      if (insurer == null || insurer.isEmpty() || insurer.length() > 100 || outcome == null
          || outcome.offered() != (quote != null)) throw new IllegalArgumentException("Invalid insurer entry");
    }
  }

  record Result(Availability availability, Reason reason, Provider provider, Instant retrievedAt,
      List<InsurerEntry> insurers, BigDecimal costUsd) {
    public Result {
      insurers = insurers == null ? null : List.copyOf(insurers);
      if (availability == null || provider == null || retrievedAt == null || insurers == null
          || (availability == Availability.AVAILABLE && reason != null)
          || (availability != Availability.AVAILABLE && (reason == null || !insurers.isEmpty()))
          || (availability == Availability.TIMED_OUT && reason != Reason.TIMED_OUT)
          || (availability == Availability.RATE_LIMITED && reason != Reason.RATE_LIMITED)
          || (costUsd != null && costUsd.signum() < 0)) throw new IllegalArgumentException("Invalid insurance quote result");
    }
    public String notice() {
      if (availability == Availability.AVAILABLE)
        return provider.synthetic() ? "Synthetic insurance quotes for development only." : null;
      return switch (reason) {
        case TIMED_OUT -> "The insurance partner timed out. No quote was substituted.";
        case RATE_LIMITED -> "The insurance partner's request limit was reached. No quote was substituted.";
        case STALE_RESPONSE -> "The insurance partner's response was too old to show. No quote was substituted.";
        case INVALID_RESPONSE -> "The insurance partner's response was invalid. No quote was substituted.";
        case PROVIDER_UNAVAILABLE -> "The insurance partner is unavailable. No quote was substituted.";
      };
    }
  }
}
