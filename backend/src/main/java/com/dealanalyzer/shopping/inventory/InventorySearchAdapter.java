package com.dealanalyzer.shopping.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Licensed search only. Vendor payloads and URL fetching are deliberately outside this boundary. */
public interface InventorySearchAdapter {
  /** Code-owned identity and executed licence terms, never copied from a remote response. */
  Provider provider();
  SearchResult search(InventorySearchCriteria criteria, int radiusMiles);

  record Provider(String name, String version, String licenceMetadata, boolean synthetic) {
    public Provider {
      if (name == null || name.isBlank() || name.length() > 64 || version == null || version.isBlank()
          || version.length() > 100 || licenceMetadata == null || licenceMetadata.isBlank()
          || licenceMetadata.length() > 100) throw new IllegalArgumentException("Inventory provider metadata required");
    }
  }
  enum Availability { AVAILABLE, UNAVAILABLE, TIMED_OUT, RATE_LIMITED }
  record SearchResult(Availability availability, Provider provider, Instant retrievedAt,
      List<InventoryListing> listings, BigDecimal costUsd) {
    public SearchResult {
      listings = List.copyOf(listings);
      if (availability == null || provider == null || retrievedAt == null
          || (availability != Availability.AVAILABLE && !listings.isEmpty())
          || (costUsd != null && costUsd.signum() < 0)) throw new IllegalArgumentException("Invalid inventory result");
    }
    public String notice() {
      return switch (availability) {
        case AVAILABLE -> provider.synthetic() ? "Synthetic inventory for development; these vehicles are not for sale." : null;
        case UNAVAILABLE -> "Inventory provider unavailable. No listings were substituted.";
        case TIMED_OUT -> "Inventory search timed out. Try again later.";
        case RATE_LIMITED -> "Inventory provider request limit reached. Try again later.";
      };
    }
  }
}
