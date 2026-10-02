package com.dealanalyzer.market;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;
import com.dealanalyzer.market.MarketEvidence.Confidence;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class DeterministicFakeMarketDataProvider implements MarketDataProvider, DevelopmentEvidenceAdapter {
  private static final FakeProviderResponse FIXTURE =
      new FakeProviderResponse(24_500_00L, 27_000_00L, "high", "2026-08-01T00:00:00Z");

  @Override
  public MarketEvidence assess(VehicleInput vehicle) {
    return new MarketEvidence(
        FIXTURE.wholesaleFloorMinor(),
        FIXTURE.retailCeilingMinor(),
        Confidence.valueOf(FIXTURE.confidence().toUpperCase()),
        Instant.parse(FIXTURE.observedAt()),
        "Synthetic licensed-market fixture",
        "synthetic-market-fixture.v1");
  }

  @Override
  public ProviderResult retrieve(MarketVehicle vehicle) {
    ProviderSnapshot snapshot = new ProviderSnapshot(
        "DETERMINISTIC_LICENSED_FIXTURE",
        "deterministic-market-provider.v1",
        "market-provider.v1",
        "synthetic fixture; display and cache permitted",
        "fixture://market/accord-raleigh-v1",
        Instant.now());
    if (vehicle == null || !"Honda".equalsIgnoreCase(vehicle.make())
        || !"Accord".equalsIgnoreCase(vehicle.model()))
      return ProviderResult.unavailable("No development fixture covers this vehicle.", snapshot);
    return ProviderResult.available(snapshot, List.of(
        observation("market-001", 2021, "Honda", "Accord", "EX-L", 38_000L,
            2_500_000L, "2026-08-25T12:00:00Z"),
        observation("market-002", 2021, "Honda", "Accord", "EX-L", 42_000L,
            2_450_000L, "2026-08-22T12:00:00Z"),
        observation("market-003", 2022, "Honda", "Accord", "EX-L", 30_000L,
            2_700_000L, "2026-08-20T12:00:00Z"),
        observation("market-004", 2020, "Honda", "Accord", "EX-L", 50_000L,
            2_300_000L, "2026-08-18T12:00:00Z"),
        observation("market-001", 2021, "Honda", "Accord", "EX-L", 38_000L,
            2_500_000L, "2026-08-25T12:00:00Z"),
        observation("market-stale", 2021, "Honda", "Accord", "EX-L", 40_000L,
            2_400_000L, "2026-05-01T12:00:00Z"),
        observation("market-civic", 2021, "Honda", "Civic", "Touring", 35_000L,
            2_350_000L, "2026-08-23T12:00:00Z"),
        observation("market-impossible", 2021, "Honda", "Accord", "EX-L", 39_000L,
            999L, "2026-08-24T12:00:00Z")));
  }

  private static ComparableObservation observation(
      String id, int year, String make, String model, String trim, long mileage,
      long priceMinor, String observedAt) {
    return new ComparableObservation(
        id,
        "https://licensed.example/market/" + id,
        year,
        make,
        model,
        trim,
        mileage,
        priceMinor,
        "USD",
        "Raleigh-Durham, NC",
        Instant.now().minus(java.time.Duration.between(
            Instant.parse(observedAt), Instant.parse("2026-08-27T12:00:00Z"))));
  }

  private record FakeProviderResponse(
      long wholesaleFloorMinor,
      long retailCeilingMinor,
      String confidence,
      String observedAt) {}
}
