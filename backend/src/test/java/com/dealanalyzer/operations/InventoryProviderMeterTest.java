package com.dealanalyzer.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dealanalyzer.shopping.inventory.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryProviderMeterTest {
  private final InventorySearchAdapter.Provider provider = new DeterministicFakeInventorySearchAdapter().provider();
  private ProviderUsageStore store() {
    var store = mock(ProviderUsageStore.class);
    when(store.startInventory("account", "journey", provider, "query")).thenReturn("usage");
    return store;
  }
  @Test void genuineTimeoutIsBoundedRecordedAndStated() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofMillis(50), 1);
    try {
      var result = meter.callInventory("account", "journey", provider, "query", () -> {
        try { Thread.sleep(5000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        return null;
      });
      assertThat(result.availability()).isEqualTo(InventorySearchAdapter.Availability.TIMED_OUT);
      assertThat(result.notice()).contains("timed out"); assertThat(result.listings()).isEmpty();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
      verify(store).inventoryLatency(eq("usage"), anyLong());
    } finally { meter.close(); }
  }
  @Test void rateLimitAndStatedOutageKeepTheirMetadataAndZeroResults() {
    for (var availability : List.of(InventorySearchAdapter.Availability.RATE_LIMITED, InventorySearchAdapter.Availability.UNAVAILABLE)) {
      var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
      try {
        var response = new InventorySearchAdapter.SearchResult(availability, provider, Instant.now(), List.of(), BigDecimal.ZERO);
        assertThat(meter.callInventory("account", "journey", provider, "query", () -> response)).isEqualTo(response);
        verify(store).inventoryResult("usage", response);
        verify(store).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
        verify(store).inventoryLatency(eq("usage"), anyLong());
      } finally { meter.close(); }
    }
  }
  @Test void closedProviderPoolIsUnavailableRatherThanAClaimedTimeout() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    meter.close();
    var invoked = new java.util.concurrent.atomic.AtomicBoolean();
    var result = meter.callInventory("account", "journey", provider, "query", () -> {
      invoked.set(true); return null;
    });
    assertThat(invoked).isFalse();
    assertThat(result.availability()).isEqualTo(InventorySearchAdapter.Availability.UNAVAILABLE);
    verify(store).finish("usage", ProviderMeter.Status.PROVIDER_UNAVAILABLE);
    verify(store).inventoryResult("usage", result);
    verify(store).inventoryLatency(eq("usage"), anyLong());
  }
  @Test void providerExceptionsAndInvalidMetadataNeverExposeRemoteTextOrInventResults() {
    var store = store(); var meter = new ProviderMeter(store, Duration.ofSeconds(1), 1);
    try {
      var failure = meter.callInventory("account", "journey", provider, "query",
          () -> { throw new IllegalStateException("secret upstream response"); });
      assertThat(failure.notice()).doesNotContain("secret"); assertThat(failure.listings()).isEmpty();
      verify(store).finish("usage", ProviderMeter.Status.PROVIDER_FAILURE);
      var wrong = new InventorySearchAdapter.Provider("OTHER", "v1", "OTHER_TERMS", false);
      var invalid = meter.callInventory("account", "journey", provider, "query", () ->
          new InventorySearchAdapter.SearchResult(InventorySearchAdapter.Availability.AVAILABLE, wrong, Instant.now(), List.of(), null));
      assertThat(invalid.availability()).isEqualTo(InventorySearchAdapter.Availability.UNAVAILABLE);
      verify(store).finish("usage", ProviderMeter.Status.CONTRACT_FAILURE);
    } finally { meter.close(); }
  }
}
