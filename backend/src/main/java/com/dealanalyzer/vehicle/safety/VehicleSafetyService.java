package com.dealanalyzer.vehicle.safety;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VehicleSafetyService {
  private final EphemeralBuyingCaseService access;
  private final VehicleEvidenceService vehicles;
  private final VehicleSafetyProvider provider;
  private final ProviderMeter meter;
  private final Duration ttl;
  private final int maxEntries;
  private record Entry(VehicleSafetyProvider.Facts facts, Instant expiresAt) {}
  private final LinkedHashMap<VehicleSafetyProvider.Vehicle, Entry> cache = new LinkedHashMap<>();

  public VehicleSafetyService(EphemeralBuyingCaseService access, VehicleEvidenceService vehicles,
      VehicleSafetyProvider provider, ProviderMeter meter,
      @Value("${vehicle-safety.cache-ttl:PT6H}") Duration ttl,
      @Value("${vehicle-safety.cache-max-entries:500}") int maxEntries) {
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(1)) > 0 || maxEntries < 1 || maxEntries > 10000)
      throw new IllegalArgumentException("Invalid vehicle safety cache bounds");
    this.access = access; this.vehicles = vehicles; this.provider = provider; this.meter = meter;
    this.ttl = ttl; this.maxEntries = maxEntries;
  }
  public record View(VehicleSafetyProvider.Facts facts, String vin) {}
  @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public View get(String caseId, String token) {
    access.reopen(caseId, token);
    VehicleEvidenceService.VehicleEvidenceView evidence;
    try { evidence = vehicles.get(caseId, token); }
    catch (InvalidVehicleEvidenceException missing) { return new View(absent("MISSING_VEHICLE", null), null); }
    var car = evidence.vehicle();
    if (!evidence.reliableForAnalysis() || car.year() == null || car.make() == null || car.make().isBlank()
        || car.model() == null || car.model().isBlank())
      return new View(absent("MISSING_VEHICLE", null), car.vin());
    var vehicle = new VehicleSafetyProvider.Vehicle(car.year(), car.make(), car.model());
    return new View(retrieve(caseId, vehicle), car.vin());
  }

  /** Recheck model identity after the report takes its case lock; never freeze another car's lookup. */
  public View snapshot(View retrieved, VehicleEvidenceService.VehicleEvidenceView evidence) {
    if (evidence == null) return new View(absent("MISSING_VEHICLE", null), null);
    var car = evidence.vehicle();
    if (!evidence.reliableForAnalysis() || car.year() == null || car.make() == null || car.make().isBlank()
        || car.model() == null || car.model().isBlank())
      return new View(absent("MISSING_VEHICLE", null), car.vin());
    var current = new VehicleSafetyProvider.Vehicle(car.year(), car.make(), car.model());
    var found = retrieved == null ? null : retrieved.facts().vehicle();
    boolean same = found != null && found.year() == current.year()
        && found.make().equalsIgnoreCase(current.make()) && found.model().equalsIgnoreCase(current.model());
    return new View(same ? retrieved.facts() : absent("UNAVAILABLE", current), car.vin());
  }
  private VehicleSafetyProvider.Facts retrieve(String caseId, VehicleSafetyProvider.Vehicle vehicle) {
    var key = new VehicleSafetyProvider.Vehicle(vehicle.year(), vehicle.make().toLowerCase(Locale.ROOT), vehicle.model().toLowerCase(Locale.ROOT));
    synchronized (cache) {
      cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(Instant.now()));
      var cached = cache.get(key); if (cached != null) return cached.facts();
    }
    VehicleSafetyProvider.Facts result;
    try {
      result = meter.call(caseId, ProviderMeter.Operation.VEHICLE_SAFETY, provider, 1,
          () -> provider.retrieve(vehicle), facts -> {
            if (!"AVAILABLE".equals(facts.status()) || !vehicle.equals(facts.vehicle()) || facts.retrievedAt() == null)
              throw new IllegalArgumentException("Invalid safety evidence");
            return ProviderMeter.Status.SUCCESS;
          });
    } catch (ProviderMeter.Failure failure) { return absent("UNAVAILABLE", vehicle); }
    synchronized (cache) {
      if (cache.size() >= maxEntries) cache.remove(cache.keySet().iterator().next());
      cache.put(key, new Entry(result, Instant.now().plus(ttl)));
    }
    return result;
  }
  private VehicleSafetyProvider.Facts absent(String status, VehicleSafetyProvider.Vehicle vehicle) {
    return new VehicleSafetyProvider.Facts(status, provider instanceof FakeVehicleSafetyProvider
        ? "Development sample — not NHTSA data" : "NHTSA", Instant.now(), vehicle, List.of(), List.of());
  }
}
