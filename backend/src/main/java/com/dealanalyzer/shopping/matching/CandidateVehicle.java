package com.dealanalyzer.shopping.matching;

import com.dealanalyzer.shopping.inventory.InventoryListing;
import java.time.Instant;
import java.util.List;

/**
 * A listing that meets every must-have the listing states, with the reasons in plain words: which
 * criteria and reference details it matches, which it misses, and which the listing does not state.
 */
public record CandidateVehicle(String listingId, String vin, String title, Integer year, String make, String model,
    String trim, Long mileage, Integer distanceMiles, Long advertisedPriceMinor, String currency,
    List<Reason> matched, List<Reason> missed, List<Reason> unconfirmed, Snapshot snapshot) {
  public CandidateVehicle {
    matched = List.copyOf(matched); missed = List.copyOf(missed); unconfirmed = List.copyOf(unconfirmed);
  }

  /** Which part of the Buyer's request a reason is about. */
  public enum Kind { MUST_HAVE, NICE_TO_HAVE, REFERENCE }
  public record Reason(Kind kind, String text) {}

  /** Where and when the listing was retrieved; a STALE listing was last observed over a week before retrieval. */
  public record Snapshot(String source, Instant retrievedAt, Instant observedAt, InventoryListing.Staleness staleness,
      boolean synthetic) {}

  /** A listing left out because it states a fact that misses a must-have, with those misses. */
  public record Excluded(String listingId, String vin, String title, List<Reason> missed, Snapshot snapshot) {
    public Excluded { missed = List.copyOf(missed); }
  }
}
