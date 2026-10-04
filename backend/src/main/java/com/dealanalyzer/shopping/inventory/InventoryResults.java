package com.dealanalyzer.shopping.inventory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Shared normalization for adapters: newest observation wins a VIN or source/listing-ID duplicate. */
public final class InventoryResults {
  private InventoryResults() {}
  public static List<InventoryListing> deduplicate(List<InventoryListing> listings) {
    var unique = new LinkedHashMap<String, InventoryListing>();
    listings.stream().sorted(Comparator.comparing(InventoryListing::observedAt,
        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(InventoryListing::listingId)).forEach(row -> {
      String key = row.vin() == null ? row.source() + ":" + row.listingId() : row.vin();
      unique.putIfAbsent(key, row);
    });
    return List.copyOf(unique.values());
  }

  public static boolean valid(InventorySearchAdapter.SearchResult result, InventorySearchAdapter.Provider provider) {
    return result != null && !result.retrievedAt().isAfter(java.time.Instant.now().plusSeconds(60))
        && provider.equals(result.provider())
        && result.listings().equals(deduplicate(result.listings()))
        && result.listings().stream().allMatch(row -> row.source().equals(provider.name())
            && row.licenceMetadata().equals(provider.licenceMetadata()) && row.synthetic() == provider.synthetic()
            && row.retrievedAt().equals(result.retrievedAt()));
  }
}
