package com.dealanalyzer.trade.valuation;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeterministicTradeValuationConformanceTest extends TradeValuationAdapterConformance {
  @Override protected Instant now() { return Instant.parse("2026-10-09T12:00:00Z"); }
  @Override protected TradeValuationAdapter adapter(Fixture fixture) {
    return new DeterministicFakeTradeValuationAdapter(Clock.fixed(now(), ZoneOffset.UTC),
        DeterministicFakeTradeValuationAdapter.Scenario.valueOf(fixture.name()));
  }
  @Test void fixedClockProducesRepeatableLabelledSyntheticFixtures() {
    for (var fixture : Fixture.values()) {
      var adapter = adapter(fixture);
      var first = adapter.value(vehicle(fixture), "27601");
      assertThat(adapter.value(vehicle(fixture), "27601")).isEqualTo(first);
      assertThat(first.provider().synthetic()).isTrue();
      assertThat(first.provider().name()).contains("SYNTHETIC");
      assertThat(first.costUsd()).isZero();
      if (first.range() != null) assertThat(first.notice()).contains("Synthetic", "development");
    }
    var normal = adapter(Fixture.NORMAL).value(vehicle(Fixture.NORMAL), "27601").range();
    var wide = adapter(Fixture.WIDE_RANGE).value(vehicle(Fixture.WIDE_RANGE), "27601").range();
    assertThat(wide.highMinor() - wide.lowMinor()).isGreaterThan(normal.highMinor() - normal.lowMinor());
    assertThat(adapter(Fixture.HIGH_MILEAGE).value(vehicle(Fixture.HIGH_MILEAGE), "27601").range().typicalMinor())
        .isLessThan(normal.typicalMinor());
  }
  @Test void invalidAmountsMetadataAndZipAreRejectedAndCurrencyIsNormalized() {
    assertThat(new TradeValuationAdapter.Range(1, 2, 3, " usd ").currency()).isEqualTo("USD");
    assertThatThrownBy(() -> new TradeValuationAdapter.Range(3, 2, 1, "USD")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradeValuationAdapter.Range(-1, 2, 3, "USD")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradeValuationAdapter.Provider("", "v1", "terms", false)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter(Fixture.NORMAL).value(vehicle(Fixture.NORMAL), "https://example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TradeValuationContract.zip(" 27601 ")).isEqualTo("27601");
  }
}
