package com.dealanalyzer.listing;

import java.time.Instant;

public interface ListingEvidenceProvider {
  ListingRetrieval retrieve(String listingUrl);

  enum RetrievalStatus { SUPPORTED, UNSUPPORTED, UNAVAILABLE }

  record ListingRetrieval(
      RetrievalStatus status, NormalizedListing listing, String message) {
    public static ListingRetrieval supported(NormalizedListing listing) {
      return new ListingRetrieval(RetrievalStatus.SUPPORTED, listing, null);
    }

    public static ListingRetrieval unsupported(String message) {
      return new ListingRetrieval(RetrievalStatus.UNSUPPORTED, null, message);
    }

    public static ListingRetrieval unavailable(String message) {
      return new ListingRetrieval(RetrievalStatus.UNAVAILABLE, null, message);
    }
  }

  record NormalizedListing(
      String providerListingId,
      String listingUrl,
      String vin,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      Long advertisedPriceMinor,
      String currency,
      NormalizedDealer dealer,
      String source,
      Instant retrievedAt,
      String providerVersion,
      String licenseMetadata,
      String artifactReference) {}

  record NormalizedDealer(
      String providerDealerId, String name, String city, String state) {}
}
