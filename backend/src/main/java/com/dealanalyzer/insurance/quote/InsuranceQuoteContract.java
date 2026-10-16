package com.dealanalyzer.insurance.quote;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import static com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.*;

/** Stale, expired or malformed partner responses never reach the Buyer as quotes. */
public final class InsuranceQuoteContract {
  public static final Duration MAX_AGE = Duration.ofHours(1);
  private InsuranceQuoteContract() {}

  public static Reason failure(Result result, Provider provider, Instant now) {
    if (result == null || !provider.equals(result.provider())
        || result.retrievedAt().isAfter(now.plusSeconds(60))) return Reason.INVALID_RESPONSE;
    if (result.retrievedAt().isBefore(now.minus(MAX_AGE))) return Reason.STALE_RESPONSE;
    var insurers = new HashSet<String>();
    for (var entry : result.insurers()) {
      if (!insurers.add(entry.insurer().toLowerCase(Locale.ROOT))) return Reason.INVALID_RESPONSE;
      var quote = entry.quote();
      if (quote == null) continue;
      if (quote.expiresAt() == null || quote.continueUrl() == null
          || quote.quotedAt().isAfter(result.retrievedAt().plusSeconds(60))
          || !quote.expiresAt().isAfter(result.retrievedAt())) return Reason.INVALID_RESPONSE;
      if (quote.quotedAt().isBefore(now.minus(MAX_AGE))) return Reason.STALE_RESPONSE;
    }
    return null;
  }

  public static Result unavailable(Provider provider, Reason reason, Instant now, BigDecimal cost) {
    var availability = reason == Reason.TIMED_OUT ? Availability.TIMED_OUT
        : reason == Reason.RATE_LIMITED ? Availability.RATE_LIMITED : Availability.UNAVAILABLE;
    return new Result(availability, reason, provider, now, java.util.List.of(), cost);
  }
}
