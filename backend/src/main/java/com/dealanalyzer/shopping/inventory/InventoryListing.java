package com.dealanalyzer.shopping.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Unknown facts remain null. STALE listings are retained for explanation, never silently freshened. */
public record InventoryListing(String listingId, Dealer dealer, String vin, Facts facts, Long advertisedPriceMinor,
    String currency, String source, String licenceMetadata, Instant retrievedAt, Instant observedAt,
    Staleness staleness, boolean synthetic) {
  public static final Duration FRESHNESS = Duration.ofDays(7);
  public enum Staleness { CURRENT, STALE, UNKNOWN }
  public record Dealer(String id, String publicName, String locality, String jurisdiction) {
    public Dealer {
      id = required(id); publicName = required(publicName); locality = clean(locality); jurisdiction = clean(jurisdiction);
    }
  }
  public record Facts(Integer year, String make, String model, String trim, Long mileage, List<String> features,
      Integer distanceMiles) {
    public Facts {
      make = clean(make); model = clean(model); trim = clean(trim);
      features = features == null ? List.of() : features.stream().map(InventoryListing::required).distinct().sorted().toList();
      if ((year != null && (year < 1886 || year > 9999)) || (mileage != null && mileage < 0)
          || (distanceMiles != null && distanceMiles < 0)) throw new IllegalArgumentException("Invalid inventory facts");
    }
  }
  public InventoryListing {
    listingId = required(listingId); source = required(source); licenceMetadata = required(licenceMetadata);
    vin = clean(vin); vin = vin == null ? null : vin.toUpperCase(Locale.ROOT);
    currency = clean(currency); currency = currency == null ? null : currency.toUpperCase(Locale.ROOT);
    if (dealer == null || facts == null || retrievedAt == null
        || (vin != null && !vin.matches("[A-HJ-NPR-Z0-9]{17}"))
        || (advertisedPriceMinor != null && (advertisedPriceMinor < 0 || currency == null))
        || (currency != null && !currency.matches("[A-Z]{3}"))
        || (observedAt != null && observedAt.isAfter(retrievedAt))) throw new IllegalArgumentException("Invalid inventory listing");
    // Derive freshness from observation time, not a vendor's claimed flag.
    staleness = observedAt == null ? Staleness.UNKNOWN :
        observedAt.isBefore(retrievedAt.minus(FRESHNESS)) ? Staleness.STALE : Staleness.CURRENT;
  }
  private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
  private static String required(String value) {
    String normalized = clean(value);
    if (normalized == null) throw new IllegalArgumentException("Inventory identity required");
    return normalized;
  }
}
