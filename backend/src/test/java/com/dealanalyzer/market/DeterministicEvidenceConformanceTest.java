package com.dealanalyzer.market;

import com.dealanalyzer.listing.ListingEvidenceProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DeterministicEvidenceConformanceTest extends EvidenceAdapterConformance {
  protected MarketDataProvider market() { return new DeterministicFakeMarketDataProvider(); }
  protected ListingEvidenceProvider listing() {
    // Resolve the actual package-private adapter through the same component configuration as startup.
    try (var context = new AnnotationConfigApplicationContext(EvidenceProviderProductionGuardTest.Adapters.class)) {
      return context.getBean(ListingEvidenceProvider.class);
    }
  }
  protected MarketDataProvider.ProviderResult unavailableMarket() {
    return market().retrieve(new MarketDataProvider.MarketVehicle(
        null, 2021, "Unsupported", "Vehicle", null, 0L, "27604"));
  }
  protected String supportedListingUrl() { return "https://licensed.example/vehicles/1HGCM82633A004352"; }
  protected String unavailableListingUrl() { return "https://unsupported.example/vehicle"; }
}
