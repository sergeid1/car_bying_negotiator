package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dealanalyzer.trade.valuation.TradeValuationAdapter.*;
import com.dealanalyzer.trade.valuation.*;
import com.dealanalyzer.trade.TradeService.Condition;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeValuationProviderMeterTest {
  private final Provider provider = new Provider("LICENSED_TEST", "v1", "TEST_TERMS", false);
  private final TradeVehicle vehicle = new TradeVehicle("1HGCM82633A004352", 60000, Condition.GOOD, "EX");
  private ProviderUsageStore store() {
    var store = mock(ProviderUsageStore.class);
    when(store.startTradeValuation("case", provider)).thenReturn("usage");
    return store;
  }
  private Result available(Instant retrieved, Instant observed) {
    return new Result(Availability.AVAILABLE, null, provider, retrieved, observed, Condition.GOOD,
        new Range(1400000, 1600000, 1800000, "USD"), new BigDecimal("0.025"));
  }
  @Test void successRateLimitAndOutageRecordResultCostStatusAndLatency() {
    for (var availability : Availability.values()) {
      var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
      try {
        var reason = availability == Availability.TIMED_OUT ? Reason.TIMED_OUT
            : availability == Availability.RATE_LIMITED ? Reason.RATE_LIMITED : Reason.PROVIDER_UNAVAILABLE;
        var result = availability == Availability.AVAILABLE ? available(Instant.now(), Instant.now())
            : TradeValuationContract.unavailable(provider, reason, Instant.now(), BigDecimal.ZERO);
        assertThat(meter.callTradeValuation("case", provider, vehicle, () -> result)).isEqualTo(result);
        verify(store).tradeValuationResult("usage", result);
        verify(store).finish("usage", availability == Availability.AVAILABLE ? ProviderMeter.Status.SUCCESS : ProviderMeter.Status.PROVIDER_UNAVAILABLE);
        verify(store).tradeValuationLatency(eq("usage"), longThat(ms -> ms >= 0));
      } finally { meter.close(); }
    }
  }
  @Test void genuineTimeoutIsBoundedAndReturnsNoRangeOrInventedCost() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofMillis(50), 1);
    try {
      var result = meter.callTradeValuation("case", provider, vehicle, () -> {
        try { Thread.sleep(5000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return null;
      });
      assertThat(result.reason()).isEqualTo(Reason.TIMED_OUT);
      assertThat(result.range()).isNull(); assertThat(result.costUsd()).isNull();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
      verify(store).tradeValuationResult("usage", result);
      verify(store).tradeValuationLatency(eq("usage"), anyLong());
    } finally { meter.close(); }
  }
  @Test void staleOrFutureEvidenceIsRejectedAndReportedCostIsKept() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      for (var response : java.util.List.of(available(Instant.now(), Instant.now().minus(TradeValuationContract.MAX_AGE).minusSeconds(1)),
          available(Instant.now().minus(TradeValuationContract.MAX_AGE).minusSeconds(1), Instant.now().minus(TradeValuationContract.MAX_AGE).minusSeconds(1)),
          available(Instant.now().plusSeconds(120), Instant.now()), available(Instant.now(), Instant.now().plusSeconds(120)))) {
        var result = meter.callTradeValuation("case", provider, vehicle, () -> response);
        assertThat(result.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.range()).isNull(); assertThat(result.costUsd()).isEqualByComparingTo("0.025");
      }
      verify(store, times(4)).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }
  @Test void nullMissingMetadataAndExceptionsStayUnavailableWithoutLeakingProviderText() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var failed = meter.callTradeValuation("case", provider, vehicle, () -> { throw new IllegalStateException("secret provider text"); });
      assertThat(failed.notice()).doesNotContain("secret"); assertThat(failed.range()).isNull();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_FAILURE);
      var missing = meter.callTradeValuation("case", provider, vehicle, () -> null);
      assertThat(missing.reason()).isEqualTo(Reason.INVALID_RESPONSE); assertThat(missing.range()).isNull();
      var malformed = meter.callTradeValuation("case", provider, vehicle, () -> new Result(Availability.AVAILABLE,
          null, provider, Instant.now(), null, Condition.GOOD, new Range(1, 2, 3, "USD"), null));
      assertThat(malformed.range()).isNull();
      var wrong = new Provider("WRONG", "v1", "WRONG_TERMS", false);
      var invalid = meter.callTradeValuation("case", provider, vehicle, () ->
          new Result(Availability.AVAILABLE, null, wrong, Instant.now(), Instant.now(), Condition.GOOD, new Range(1, 2, 3, "USD"), null));
      assertThat(invalid.reason()).isEqualTo(Reason.INVALID_RESPONSE); assertThat(invalid.range()).isNull();
    } finally { meter.close(); }
  }
  @Test void closedPoolDoesNotClaimATimeoutOrInvokeProvider() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1); meter.close();
    var invoked = new java.util.concurrent.atomic.AtomicBoolean();
    var result = meter.callTradeValuation("case", provider, vehicle, () -> { invoked.set(true); return null; });
    assertThat(invoked).isFalse(); assertThat(result.reason()).isEqualTo(Reason.PROVIDER_UNAVAILABLE);
    verify(store).tradeValuationLatency(eq("usage"), anyLong());
  }
  @Test void mismatchedConditionOrMissingInputFactsCannotProduceAUsableRange() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var response = available(Instant.now(), Instant.now());
      for (var input : java.util.List.of(new TradeVehicle(vehicle.vin(), vehicle.mileage(), Condition.POOR, "EX"),
          new TradeVehicle(vehicle.vin(), null, Condition.GOOD, "EX"))) {
        var result = meter.callTradeValuation("case", provider, input, () -> response);
        assertThat(result.reason()).isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(result.range()).isNull();
      }
      verify(store, times(2)).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }
}
