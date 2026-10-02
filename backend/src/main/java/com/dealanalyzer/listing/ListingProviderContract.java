package com.dealanalyzer.listing;

import java.time.Instant;
import com.dealanalyzer.listing.ListingEvidenceProvider.ListingRetrieval;

public final class ListingProviderContract {
  private ListingProviderContract() {}

  public static boolean valid(ListingRetrieval result, Instant now) {
    if (result == null || result.status() == null) return false;
    if (result.status() != ListingEvidenceProvider.RetrievalStatus.SUPPORTED)
      return result.listing() == null && !blank(result.message());
    var listing = result.listing();
    return listing != null && !blank(listing.providerListingId()) && !blank(listing.source())
        && !blank(listing.providerVersion()) && !blank(listing.licenseMetadata())
        && !blank(listing.artifactReference()) && listing.retrievedAt() != null
        && !listing.retrievedAt().isAfter(now.plusSeconds(60))
        && !listing.retrievedAt().isBefore(now.minus(com.dealanalyzer.market.MarketProviderContract.MAX_SNAPSHOT_AGE))
        && listing.advertisedPriceMinor() != null && listing.advertisedPriceMinor() >= 0
        && listing.currency() != null && listing.currency().matches("[A-Z]{3}");
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
