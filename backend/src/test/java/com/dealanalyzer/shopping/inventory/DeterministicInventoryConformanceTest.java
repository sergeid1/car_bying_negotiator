package com.dealanalyzer.shopping.inventory;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.journey.ShoppingBrief;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicInventoryConformanceTest extends InventorySearchAdapterConformance {
  static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  @Override protected InventorySearchAdapter adapter(InventorySearchAdapter.Availability scenario) {
    return new DeterministicFakeInventorySearchAdapter(Clock.fixed(NOW, ZoneOffset.UTC), scenario);
  }
  @Override protected InventorySearchCriteria criteria() {
    return InventorySearchCriteria.from(new ShoppingBrief("not-sent-to-provider", 2, "reference.v1", 2020, 2024,
        new ShoppingBrief.Choice("toyota", "Toyota"), new ShoppingBrief.Choice("toyota-rav4", "RAV4"),
        List.of(new ShoppingBrief.Choice("toyota-rav4-xle", "XLE")), 60000, 3200000, "USD", "27601", "US-NC", 50,
        List.of(new ShoppingBrief.Choice("all-wheel-drive", "All-wheel drive")), List.of(), NOW));
  }

  @Test void repeatableLabelledFixtureContainsMatchesAndNearMisses() {
    var adapter = adapter(InventorySearchAdapter.Availability.AVAILABLE);
    var first = adapter.search(criteria(), 50);
    assertThat(adapter.search(criteria(), 50)).isEqualTo(first);
    assertThat(first.listings()).hasSize(5).allMatch(InventoryListing::synthetic);
    assertThat(first.provider().synthetic()).isTrue();
    assertThat(first.notice()).contains("Synthetic", "not for sale");
    assertThat(first.listings()).anyMatch(row -> row.advertisedPriceMinor() != null
        && row.advertisedPriceMinor() <= criteria().priceCeilingMinor() && row.facts().mileage() <= criteria().maxMileage())
        .anyMatch(row -> row.advertisedPriceMinor() != null && row.advertisedPriceMinor() > criteria().priceCeilingMinor());
    assertThat(first.costUsd()).isZero();
    assertThatThrownBy(() -> adapter.search(criteria(), 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void normalizationTrimsStringsUppercasesVinAndCurrencyAndDerivesFreshness() {
    var row = new InventoryListing(" id ", new InventoryListing.Dealer(" dealer ", " Example ", " Raleigh ", "US-NC"),
        " 1hgcm82633a004352 ", new InventoryListing.Facts(2024, " Toyota ", " RAV4 ", " ", 1000L,
            List.of(" heated-seats ", "heated-seats"), 10), 1234567L, "usd", " source ", " licence ", NOW,
        NOW.minusSeconds(8 * 86400), InventoryListing.Staleness.CURRENT, true);
    assertThat(row.listingId()).isEqualTo("id"); assertThat(row.vin()).isEqualTo("1HGCM82633A004352");
    assertThat(row.currency()).isEqualTo("USD"); assertThat(row.facts().trim()).isNull();
    assertThat(row.advertisedPriceMinor()).isEqualTo(1234567L);
    assertThat(row.facts().features()).containsExactly("heated-seats");
    assertThat(row.staleness()).isEqualTo(InventoryListing.Staleness.STALE);
  }
}
