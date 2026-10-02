package com.dealanalyzer.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeterministicMarketClockTest {
  private static final Instant RETRIEVED = Instant.parse("2026-08-27T12:00:00Z");
  private static final MarketDataProvider.MarketVehicle VEHICLE = new MarketDataProvider.MarketVehicle(
      null, 2021, "Honda", "Accord", "EX-L", 38_000L, "27604");

  @Test void aFixedClockProducesIdenticalSnapshotsAndAssessments() {
    var clock = Clock.fixed(RETRIEVED, ZoneOffset.UTC);
    var provider = new DeterministicFakeMarketDataProvider(clock);
    var first = provider.retrieve(VEHICLE);
    assertThat(provider.retrieve(VEHICLE)).isEqualTo(first);
    assertThat(new DeterministicFakeMarketDataProvider(clock).retrieve(VEHICLE)).isEqualTo(first);
    assertThat(first.snapshot().retrievedAt()).isEqualTo(RETRIEVED);
    assertThat(first.observations().getFirst().observedAt()).isEqualTo(RETRIEVED.minus(Duration.ofDays(2)));
    var policy = new MarketAssessmentPolicy();
    assertThat(policy.assess(VEHICLE, first)).isEqualTo(policy.assess(VEHICLE, provider.retrieve(VEHICLE)));
    assertThat(policy.assess(VEHICLE, first).sampleSize()).isEqualTo(4);
    assertThat(provider.retrieve(null)).isEqualTo(provider.retrieve(null));
  }

  @Test void retrievalReadsTheClockOnceAndDerivesEveryObservationFromThatInstant() {
    var clock = mock(Clock.class);
    when(clock.instant()).thenReturn(RETRIEVED);
    var result = new DeterministicFakeMarketDataProvider(clock).retrieve(VEHICLE);
    verify(clock, times(1)).instant();
    assertThat(result.observations()).extracting(MarketDataProvider.ComparableObservation::observedAt)
        .containsExactly(Instant.parse("2026-08-25T12:00:00Z"), Instant.parse("2026-08-22T12:00:00Z"),
            Instant.parse("2026-08-20T12:00:00Z"), Instant.parse("2026-08-18T12:00:00Z"),
            Instant.parse("2026-08-25T12:00:00Z"), Instant.parse("2026-05-01T12:00:00Z"),
            Instant.parse("2026-08-23T12:00:00Z"), Instant.parse("2026-08-24T12:00:00Z"));
  }
}
