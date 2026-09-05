package com.dealanalyzer.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.market.MarketDataProvider.ComparableObservation;
import com.dealanalyzer.market.MarketDataProvider.MarketVehicle;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import com.dealanalyzer.market.MarketDataProvider.ProviderSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketAssessmentPolicyTest {
  private static final Instant RETRIEVED = Instant.parse("2026-08-27T12:00:00Z");
  private static final MarketVehicle VEHICLE =
      new MarketVehicle(null, 2021, "Honda", "Accord", "EX-L", 40_000L, "Raleigh, NC");
  private static final ProviderSnapshot SNAPSHOT = new ProviderSnapshot(
      "RECORDED", "provider.v1", "market-provider.v1", "licensed", "fixture://test", RETRIEVED);

  @Test
  void fewerThanThreeUsableComparablesReturnsInsufficientData() {
    var result = new MarketAssessmentPolicy().assess(VEHICLE, ProviderResult.available(
        SNAPSHOT, List.of(observation("one", 2_400_000L), observation("two", 2_500_000L))));

    assertThat(result.status()).isEqualTo(MarketAssessment.Status.INSUFFICIENT_DATA);
    assertThat(result.range()).isNull();
    assertThat(result.confidence()).isEqualTo(MarketAssessment.Confidence.LOW);
    assertThat(result.sampleSize()).isEqualTo(2);
  }

  @Test
  void divergentObservationsWidenRangeAndLowerConfidence() {
    var result = new MarketAssessmentPolicy().assess(VEHICLE, ProviderResult.available(
        SNAPSHOT, List.of(
            observation("one", 1_800_000L),
            observation("two", 2_500_000L),
            observation("three", 3_400_000L),
            observation("four", 3_500_000L),
            observation("five", 3_600_000L),
            observation("six", 3_700_000L))));

    assertThat(result.status()).isEqualTo(MarketAssessment.Status.AVAILABLE);
    assertThat(result.confidence()).isEqualTo(MarketAssessment.Confidence.LOW);
    assertThat(result.range().lowMinor()).isLessThan(1_800_000L);
    assertThat(result.range().highMinor()).isGreaterThan(3_700_000L);
    assertThat(result.limitation()).contains("diverge");
  }

  @Test
  void providerFailureIsExplicitlyUnavailable() {
    var result = new MarketAssessmentPolicy().assess(
        VEHICLE, ProviderResult.unavailable("provider timeout", SNAPSHOT));

    assertThat(result.status()).isEqualTo(MarketAssessment.Status.UNAVAILABLE);
    assertThat(result.confidence()).isEqualTo(MarketAssessment.Confidence.UNAVAILABLE);
    assertThat(result.range()).isNull();
    assertThat(result.limitation()).contains("timeout");
  }

  private static ComparableObservation observation(String id, long price) {
    return new ComparableObservation(
        id, "https://example/" + id, 2021, "Honda", "Accord", "EX-L", 40_000L,
        price, "USD", "Raleigh, NC", Instant.parse("2026-08-20T12:00:00Z"));
  }
}
