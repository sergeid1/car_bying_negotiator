package com.dealanalyzer.trade.valuation;

import com.dealanalyzer.market.DevelopmentEvidenceAdapter;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Component;
import static com.dealanalyzer.trade.valuation.TradeValuationAdapter.*;

/** Fixed synthetic fixtures, no network. A supplied clock makes every scenario repeatable. */
@Component
public class DeterministicFakeTradeValuationAdapter implements TradeValuationAdapter, DevelopmentEvidenceAdapter {
  public enum Scenario { NORMAL, WIDE_RANGE, UNKNOWN_TRIM, HIGH_MILEAGE, STALE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  private final Clock clock;
  private final Scenario scenario;
  public DeterministicFakeTradeValuationAdapter() { this(Clock.systemUTC(), Scenario.NORMAL); }
  public DeterministicFakeTradeValuationAdapter(Clock clock, Scenario scenario) { this.clock = clock; this.scenario = scenario; }
  @Override public Provider provider() {
    return new Provider("SYNTHETIC_TRADE_VALUATION", "deterministic-trade.v1", "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED", true);
  }
  @Override public Result value(TradeVehicle vehicle, String zip) {
    TradeValuationContract.zip(zip);
    var now = clock.instant();
    if (vehicle == null || !vehicle.complete()) return TradeValuationContract.unavailable(provider(), Reason.MISSING_FIELDS, now, BigDecimal.ZERO);
    Reason reason = switch (scenario) {
      case UNAVAILABLE -> Reason.PROVIDER_UNAVAILABLE;
      case TIMED_OUT -> Reason.TIMED_OUT;
      case RATE_LIMITED -> Reason.RATE_LIMITED;
      default -> null;
    };
    if (reason != null) return TradeValuationContract.unavailable(provider(), reason, now, BigDecimal.ZERO);
    boolean wide = scenario == Scenario.WIDE_RANGE || scenario == Scenario.UNKNOWN_TRIM || vehicle.trim() == null;
    boolean highMileage = scenario == Scenario.HIGH_MILEAGE || vehicle.mileage() >= 150000;
    var range = highMileage ? new Range(400000, 600000, 800000, "USD")
        : wide ? new Range(800000, 1500000, 2200000, "USD") : new Range(1400000, 1600000, 1800000, "USD");
    var observed = scenario == Scenario.STALE ? now.minus(TradeValuationContract.MAX_AGE).minusSeconds(1) : now;
    var result = new Result(Availability.AVAILABLE, null, provider(), now, observed, vehicle.condition(), range, BigDecimal.ZERO);
    var failure = TradeValuationContract.failure(result, provider(), vehicle, now);
    return failure == null ? result : new Result(Availability.UNAVAILABLE, failure, provider(), now, observed,
        vehicle.condition(), null, BigDecimal.ZERO);
  }
}
