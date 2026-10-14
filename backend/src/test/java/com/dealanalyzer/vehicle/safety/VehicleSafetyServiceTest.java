package com.dealanalyzer.vehicle.safety;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VehicleSafetyServiceTest {
  final EphemeralBuyingCaseService access = mock(EphemeralBuyingCaseService.class);
  final VehicleEvidenceService vehicles = mock(VehicleEvidenceService.class);
  final VehicleSafetyProvider provider = spy(new FakeVehicleSafetyProvider());
  final ProviderMeter meter = mock(ProviderMeter.class);

  VehicleSafetyService service(Duration ttl, int limit) {
    when(meter.call(anyString(), eq(ProviderMeter.Operation.VEHICLE_SAFETY), same(provider), eq(1L), any(), any()))
        .thenAnswer(call -> {
          var facts = ((Supplier<?>) call.getArgument(4)).get();
          @SuppressWarnings("unchecked") var classify = (Function<Object, ProviderMeter.Status>) call.getArgument(5);
          classify.apply(facts); return facts;
        });
    return new VehicleSafetyService(access, vehicles, provider, meter, ttl, limit);
  }
  void car(String make, String model) {
    when(vehicles.get("case", "token")).thenReturn(new VehicleEvidenceService.VehicleEvidenceView(
        new VehicleEvidenceService.VehicleView("1HGCM82633A004352", 2021, make, model, null, 10000L, "MANUAL", Instant.now()),
        null, null, List.of(), "VEHICLE_CONFIRMED", true));
  }
  @Test void cacheUsesNormalizedModelIdentityAndEvictsAtTheEntryBound() {
    var service = service(Duration.ofHours(1), 1);
    car("Honda", "Accord"); var first = service.get("case", "token");
    car("HONDA", "ACCORD"); assertThat(service.get("case", "token").facts()).isEqualTo(first.facts());
    verify(provider, times(1)).retrieve(any());
    car("Toyota", "Camry"); service.get("case", "token");
    car("Honda", "Accord"); service.get("case", "token");
    verify(provider, times(3)).retrieve(any());
    verify(access, times(4)).reopen("case", "token");
  }
  @Test void cacheExpiresAndDoesNotReturnOldRetrievalTimes() throws Exception {
    var service = service(Duration.ofMillis(10), 1);
    car("Honda", "Accord"); var first = service.get("case", "token");
    Thread.sleep(20);
    assertThat(service.get("case", "token").facts().retrievedAt()).isAfter(first.facts().retrievedAt());
    verify(provider, times(2)).retrieve(any());
  }
  @Test void unavailableHasNoRecallCountAndIsNotCached() {
    var service = service(Duration.ofHours(1), 1); car("Honda", "Accord");
    when(meter.call(anyString(), any(), any(), anyLong(), any(), any()))
        .thenThrow(new ProviderMeter.Failure(ProviderMeter.Status.PROVIDER_UNAVAILABLE));
    for (int i = 0; i < 2; i++) {
      var facts = service.get("case", "token").facts();
      assertThat(facts.status()).isEqualTo("UNAVAILABLE"); assertThat(facts.recalls()).isEmpty();
    }
    verify(meter, times(2)).call(anyString(), any(), any(), anyLong(), any(), any());
  }
  @Test void missingOrUnconfirmedIdentityNeverCallsTheProvider() {
    var service = service(Duration.ofHours(1), 1); car(null, null);
    assertThat(service.get("case", "token").facts().status()).isEqualTo("MISSING_VEHICLE");
    verifyNoInteractions(provider);
  }
  @Test void invalidCacheSettingsFailStartup() {
    assertThatThrownBy(() -> service(Duration.ofDays(2), 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service(Duration.ofHours(1), 0)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void reportSnapshotCannotKeepFactsFromACarChangedDuringTheLookup() {
    var service = service(Duration.ofHours(1), 1); car("Honda", "Accord");
    var retrieved = service.get("case", "token");
    assertThat(service.snapshot(retrieved, vehicles.get("case", "token"))).isEqualTo(retrieved);
    car("Toyota", "Camry");
    var snapshot = service.snapshot(retrieved, vehicles.get("case", "token"));
    assertThat(snapshot.facts().status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.facts().vehicle().model()).isEqualTo("Camry");
    assertThat(snapshot.facts().recalls()).isEmpty();
  }
}
