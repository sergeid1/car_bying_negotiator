package com.dealanalyzer.shopping.inventory;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every adapter inherits this suite with recorded fixtures for each availability outcome. */
public abstract class InventorySearchAdapterConformance {
  protected abstract InventorySearchAdapter adapter(InventorySearchAdapter.Availability scenario);
  protected abstract InventorySearchCriteria criteria();

  private InventorySearchAdapter.SearchResult result(InventorySearchAdapter.Availability scenario) {
    var adapter = adapter(scenario);
    var result = adapter.search(criteria(), 50);
    assertThat(InventoryResults.valid(result, adapter.provider())).isTrue();
    return result;
  }

  @Test void normalizedFactsPricesAndMetadataArePreserved() {
    var result = result(InventorySearchAdapter.Availability.AVAILABLE);
    assertThat(result.availability()).isEqualTo(InventorySearchAdapter.Availability.AVAILABLE);
    assertThat(result.listings()).isNotEmpty();
    for (var row : result.listings()) {
      assertThat(row.dealer().id()).isNotBlank();
      assertThat(row.dealer().publicName()).isNotBlank().isEqualTo(row.dealer().publicName().strip());
      assertThat(row.retrievedAt()).isEqualTo(result.retrievedAt());
      assertThat(row.source()).isEqualTo(result.provider().name());
      assertThat(row.licenceMetadata()).isEqualTo(result.provider().licenceMetadata());
      if (row.vin() != null) assertThat(row.vin()).matches("[A-HJ-NPR-Z0-9]{17}");
      if (row.advertisedPriceMinor() != null) {
        assertThat(row.advertisedPriceMinor()).isNotNegative();
        assertThat(row.currency()).isEqualTo("USD");
      }
      if (row.facts().mileage() != null) assertThat(row.facts().mileage()).isNotNegative();
      assertThat(row.facts().features()).doesNotHaveDuplicates().isSorted();
    }
  }

  @Test void duplicatesAreRemovedByVinAndBySourceIdentityWithoutVin() {
    var rows = result(InventorySearchAdapter.Availability.AVAILABLE).listings();
    assertThat(rows.stream().filter(row -> row.vin() != null).map(InventoryListing::vin).toList()).doesNotHaveDuplicates();
    var first = rows.getFirst();
    var older = new InventoryListing("older-copy", first.dealer(), first.vin(), first.facts(),
        first.advertisedPriceMinor(), first.currency(), first.source(), first.licenceMetadata(), first.retrievedAt(),
        first.retrievedAt().minusSeconds(86400), null, first.synthetic());
    if (first.vin() != null) assertThat(InventoryResults.deduplicate(List.of(older, first))).containsExactly(first);
    var noVin = rows.stream().filter(row -> row.vin() == null).findFirst().orElseThrow();
    assertThat(InventoryResults.deduplicate(List.of(noVin, noVin))).containsExactly(noVin);
  }

  @Test void staleAndUnknownObservationTimesAreExplicit() {
    var rows = result(InventorySearchAdapter.Availability.AVAILABLE).listings();
    assertThat(rows).anyMatch(row -> row.staleness() == InventoryListing.Staleness.STALE)
        .anyMatch(row -> row.staleness() == InventoryListing.Staleness.CURRENT)
        .anyMatch(row -> row.staleness() == InventoryListing.Staleness.UNKNOWN);
    for (var row : rows) if (row.staleness() == InventoryListing.Staleness.STALE)
      assertThat(row.observedAt()).isBefore(row.retrievedAt().minus(InventoryListing.FRESHNESS));
  }

  @Test void missingVinPriceAndFactsAreUnknownRatherThanInvented() {
    var rows = result(InventorySearchAdapter.Availability.AVAILABLE).listings();
    assertThat(rows).anyMatch(row -> row.vin() == null)
        .anyMatch(row -> row.advertisedPriceMinor() == null && row.facts().mileage() == null && row.facts().year() == null);
  }

  @Test void timeoutsRateLimitsAndUnavailabilityHaveNoSubstituteListings() {
    for (var scenario : List.of(InventorySearchAdapter.Availability.UNAVAILABLE,
        InventorySearchAdapter.Availability.TIMED_OUT, InventorySearchAdapter.Availability.RATE_LIMITED)) {
      var result = result(scenario);
      assertThat(result.availability()).isEqualTo(scenario);
      assertThat(result.listings()).isEmpty();
      assertThat(result.notice()).isNotBlank();
    }
  }

  @Test void contractHasNoUrlFetchOperationOrVendorPayloadType() {
    assertThat(InventorySearchAdapter.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("provider", "search");
    var search = java.util.Arrays.stream(InventorySearchAdapter.class.getDeclaredMethods())
        .filter(method -> method.getName().equals("search")).findFirst().orElseThrow();
    assertThat(search.getParameterTypes()).containsExactly(InventorySearchCriteria.class, int.class);
    assertThat(search.getReturnType()).isEqualTo(InventorySearchAdapter.SearchResult.class);
    for (var type : List.of(InventorySearchAdapter.SearchResult.class, InventoryListing.class,
        InventoryListing.Dealer.class, InventoryListing.Facts.class))
      for (var component : type.getRecordComponents())
        assertThat(component.getGenericType().getTypeName()).matches("(?:java\\..*|com\\.dealanalyzer\\..*|boolean|int|long)");
  }
}
