package com.dealanalyzer.listing;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class DeterministicListingEvidenceProvider implements ListingEvidenceProvider {
  private static final String SUPPORTED_URL =
      "https://licensed.example/vehicles/1HGCM82633A004352";
  private static final String MISMATCH_URL =
      "https://licensed.example/vehicles/1M8GDM9AXKP042788";

  @Override
  public ListingRetrieval retrieve(String listingUrl) {
    if (!SUPPORTED_URL.equals(listingUrl) && !MISMATCH_URL.equals(listingUrl)) {
      return ListingRetrieval.unsupported(
          "This listing source is not supported; continue with the VIN or manual facts.");
    }
    Instant retrievedAt = Instant.now();
    boolean mismatch = MISMATCH_URL.equals(listingUrl);
    String listingVin = mismatch ? "1M8GDM9AXKP042788" : "1HGCM82633A004352";
    String listingId = mismatch ? "licensed-conflict-001" : "licensed-accord-001";
    return ListingRetrieval.supported(new NormalizedListing(
        listingId,
        listingUrl,
        listingVin,
        2021,
        "Honda",
        "Accord",
        "EX-L",
        38_000L,
        2_500_000L,
        "USD",
        new NormalizedDealer("triangle-honda-001", "Triangle Honda", "Raleigh", "NC"),
        "DETERMINISTIC_LICENSED_FIXTURE",
        retrievedAt,
        "listing-provider.v1",
        "synthetic fixture; display and cache permitted",
        "fixture://" + listingId));
  }
}
