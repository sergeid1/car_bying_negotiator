package com.dealanalyzer.market;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput;
import java.time.Instant;
import java.util.List;

public interface MarketDataProvider {
  MarketEvidence assess(VehicleInput vehicle);

  ProviderResult retrieve(MarketVehicle vehicle);

  record MarketVehicle(
      String vin,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      String geography) {}

  record ProviderResult(
      Availability availability,
      String unavailableReason,
      ProviderSnapshot snapshot,
      List<ComparableObservation> observations) {
    public ProviderResult {
      observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public static ProviderResult available(
        ProviderSnapshot snapshot, List<ComparableObservation> observations) {
      return new ProviderResult(Availability.AVAILABLE, null, snapshot, observations);
    }

    public static ProviderResult unavailable(
        String reason, ProviderSnapshot snapshot) {
      return new ProviderResult(Availability.UNAVAILABLE, reason, snapshot, List.of());
    }
  }

  enum Availability { AVAILABLE, UNAVAILABLE }

  record ProviderSnapshot(
      String source,
      String providerVersion,
      String schemaVersion,
      String licenseMetadata,
      String artifactReference,
      Instant retrievedAt) {}

  record ComparableObservation(
      String providerObservationId,
      String listingUrl,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      Long priceMinor,
      String currency,
      String geography,
      Instant observedAt) {}
}
