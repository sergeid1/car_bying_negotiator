package com.dealanalyzer.market;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
final class DeterministicFakeMarketDataProvider implements MarketDataProvider {
  private static final FakeProviderResponse FIXTURE =
      new FakeProviderResponse(24_500_00L, 27_000_00L, "high", "2026-08-01T00:00:00Z");

  @Override
  public MarketEvidence assess(VehicleInput vehicle) {
    return new MarketEvidence(
        FIXTURE.wholesaleFloorMinor(),
        FIXTURE.retailCeilingMinor(),
        FIXTURE.confidence().toUpperCase(),
        Instant.parse(FIXTURE.observedAt()),
        "Synthetic licensed-market fixture",
        "synthetic-market-fixture.v1");
  }

  private record FakeProviderResponse(
      long wholesaleFloorMinor,
      long retailCeilingMinor,
      String confidence,
      String observedAt) {}
}

