package com.dealanalyzer.shopping.inventory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** No network calls. Fixture facts are relative to criteria; time is supplied by a clock. */
@Component
public class DeterministicFakeInventorySearchAdapter implements InventorySearchAdapter {
  private final Clock clock;
  private final Availability availability;
  public DeterministicFakeInventorySearchAdapter() { this(Clock.systemUTC(), Availability.AVAILABLE); }
  public DeterministicFakeInventorySearchAdapter(Clock clock, Availability availability) {
    this.clock = clock; this.availability = availability;
  }
  @Override public Provider provider() {
    return new Provider("SYNTHETIC_INVENTORY", "deterministic-inventory.v1", "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED", true);
  }
  @Override public SearchResult search(InventorySearchCriteria criteria, int radiusMiles) {
    if (radiusMiles < 1 || radiusMiles > 500) throw new IllegalArgumentException("Inventory radius outside supported bounds");
    var now = clock.instant();
    if (availability != Availability.AVAILABLE) return new SearchResult(availability, provider(), now, List.of(), BigDecimal.ZERO);
    var dealer = new InventoryListing.Dealer("synthetic-dealer", "Synthetic Example Dealer", "Synthetic locality", criteria.jurisdiction());
    String trim = criteria.trims().isEmpty() ? "Synthetic trim" : criteria.trims().getFirst().label();
    var features = Stream.concat(criteria.mustHaveFeatures().stream(), criteria.niceToHaveFeatures().stream())
        .map(com.dealanalyzer.journey.ShoppingBrief.Choice::id).toList();
    var match = new InventoryListing.Facts(criteria.yearTo(), criteria.make().label(), criteria.model().label(), trim,
        (long) criteria.maxMileage() / 2, features, Math.min(10, radiusMiles));
    var near = new InventoryListing.Facts(criteria.yearTo(), criteria.make().label(), criteria.model().label(), trim,
        (long) criteria.maxMileage() + 1000, List.of(), radiusMiles);
    var missing = new InventoryListing.Facts(null, criteria.make().label(), criteria.model().label(), null, null, null, null);
    return new SearchResult(Availability.AVAILABLE, provider(), now, InventoryResults.deduplicate(List.of(
        row("match", dealer, "1HGCM82633A004352", match, criteria.priceCeilingMinor(), now, now),
        row("duplicate", dealer, "1HGCM82633A004352", match, criteria.priceCeilingMinor() + 100, now, now.minusSeconds(60)),
        row("near-miss", dealer, "1HGCM82633A004353", near, criteria.priceCeilingMinor() + 10000, now, now),
        row("stale", dealer, "1HGCM82633A004354", match, criteria.priceCeilingMinor(), now, now.minusSeconds(8 * 86400)),
        row("missing-vin", dealer, null, match, criteria.priceCeilingMinor(), now, now),
        row("missing-facts", dealer, null, missing, null, now, null))), BigDecimal.ZERO);
  }
  private InventoryListing row(String id, InventoryListing.Dealer dealer, String vin, InventoryListing.Facts facts,
      Long price, java.time.Instant now, java.time.Instant observed) {
    return new InventoryListing(id, dealer, vin, facts, price, "USD", provider().name(), provider().licenceMetadata(),
        now, observed, null, true);
  }
}
