package com.dealanalyzer.trade.valuation;

import java.time.Duration;
import java.time.Instant;
import static com.dealanalyzer.trade.valuation.TradeValuationAdapter.*;

/** Stale or malformed provider evidence never becomes a platform estimate. */
public final class TradeValuationContract {
  public static final Duration MAX_AGE = Duration.ofDays(45);
  private TradeValuationContract() {}

  public static Reason failure(Result result, Provider provider, TradeVehicle vehicle, Instant now) {
    if (result == null || !provider.equals(result.provider())
        || result.retrievedAt().isAfter(now.plusSeconds(60))) return Reason.INVALID_RESPONSE;
    if (result.retrievedAt().isBefore(now.minus(MAX_AGE))) return Reason.STALE_OBSERVATION;
    if (result.availability() == Availability.AVAILABLE) {
      if (!vehicle.complete() || result.conditionAssumption() != vehicle.condition()) return Reason.INVALID_RESPONSE;
      if (result.observedAt().isAfter(result.retrievedAt().plusSeconds(60))) return Reason.INVALID_RESPONSE;
      if (result.observedAt().isBefore(now.minus(MAX_AGE))) return Reason.STALE_OBSERVATION;
    }
    return null;
  }

  public static Result unavailable(Provider provider, Reason reason, Instant now, java.math.BigDecimal cost) {
    var availability = reason == Reason.TIMED_OUT ? Availability.TIMED_OUT
        : reason == Reason.RATE_LIMITED ? Availability.RATE_LIMITED : Availability.UNAVAILABLE;
    return new Result(availability, reason, provider, now, null, null, null, cost);
  }

  public static String zip(String zip) {
    if (zip == null || !zip.strip().matches("[0-9]{5}")) throw new IllegalArgumentException("A five-digit ZIP is required");
    return zip.strip();
  }
}
