package com.dealanalyzer.trade.valuation;

import com.dealanalyzer.trade.TradeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/** Licensed trade-in valuation only; no vendor payload or URL-fetch operation crosses this seam. */
public interface TradeValuationAdapter {
  Provider provider();
  Result value(TradeVehicle tradeVehicle, String zip);

  /** Code-owned provider identity and licence terms, never taken from a remote payload. */
  record Provider(String name, String version, String licenceMetadata, boolean synthetic) {
    public Provider {
      if (name == null || name.isBlank() || name.length() > 64 || version == null || version.isBlank()
          || version.length() > 100 || licenceMetadata == null || licenceMetadata.isBlank()
          || licenceMetadata.length() > 100) throw new IllegalArgumentException("Trade provider metadata required");
    }
  }
  enum Availability { AVAILABLE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  enum Reason { PROVIDER_UNAVAILABLE, TIMED_OUT, RATE_LIMITED, MISSING_FIELDS, STALE_OBSERVATION, INVALID_RESPONSE }
  record Range(long lowMinor, long typicalMinor, long highMinor, String currency) {
    public Range {
      currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
      if (lowMinor < 0 || typicalMinor < lowMinor || highMinor < typicalMinor || !"USD".equals(currency))
        throw new IllegalArgumentException("Invalid trade range");
    }
  }
  record Result(Availability availability, Reason reason, Provider provider, Instant retrievedAt,
      Instant observedAt, TradeService.Condition conditionAssumption, Range range, BigDecimal costUsd) {
    public Result {
      if (availability == null || provider == null || retrievedAt == null
          || (availability == Availability.AVAILABLE && (reason != null || range == null || observedAt == null
              || conditionAssumption == null))
          || (availability != Availability.AVAILABLE && (reason == null || range != null))
          || (availability == Availability.TIMED_OUT && reason != Reason.TIMED_OUT)
          || (availability == Availability.RATE_LIMITED && reason != Reason.RATE_LIMITED)
          || (costUsd != null && costUsd.signum() < 0)) throw new IllegalArgumentException("Invalid trade valuation result");
    }
    public String notice() {
      if (availability == Availability.AVAILABLE)
        return provider.synthetic() ? "Synthetic trade-in range for development only." : null;
      return switch (reason) {
        case TIMED_OUT -> "Trade valuation timed out. No value was substituted.";
        case RATE_LIMITED -> "Trade valuation request limit reached. No value was substituted.";
        case MISSING_FIELDS -> "Trade valuation unavailable because required vehicle facts are missing.";
        case STALE_OBSERVATION -> "Trade valuation unavailable because the observation is stale.";
        case INVALID_RESPONSE -> "Trade valuation unavailable because the provider response was invalid.";
        case PROVIDER_UNAVAILABLE -> "Trade valuation provider unavailable. No value was substituted.";
      };
    }
  }
}
