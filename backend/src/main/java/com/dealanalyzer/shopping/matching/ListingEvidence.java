package com.dealanalyzer.shopping.matching;

import com.dealanalyzer.shopping.inventory.InventoryListing;
import java.time.Instant;

/**
 * The only view of a listing that matching reads: what the listing states about the vehicle, its
 * advertised price for the Buyer's ceiling, and where and when it was retrieved. Who lists the vehicle
 * is deliberately not copied, so it cannot reach ordering or wording.
 */
public record ListingEvidence(String listingId, String vin, InventoryListing.Facts facts, Long advertisedPriceMinor,
    String currency, String source, Instant retrievedAt, Instant observedAt, InventoryListing.Staleness staleness,
    boolean synthetic) {
  public static ListingEvidence of(InventoryListing listing) {
    return new ListingEvidence(listing.listingId(), listing.vin(), listing.facts(), listing.advertisedPriceMinor(),
        listing.currency(), listing.source(), listing.retrievedAt(), listing.observedAt(), listing.staleness(),
        listing.synthetic());
  }
}
