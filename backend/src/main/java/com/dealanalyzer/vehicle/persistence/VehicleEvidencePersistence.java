package com.dealanalyzer.vehicle.persistence;

import com.dealanalyzer.vehicle.EstablishVehicleRequest.ManualVehicleFacts;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import com.dealanalyzer.vehicle.VehicleEvidenceService.VehicleView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VehicleEvidencePersistence {
  private final VehicleRepository vehicles;

  VehicleEvidencePersistence(VehicleRepository vehicles) {
    this.vehicles = vehicles;
  }

  @Transactional
  public VehicleView confirmVin(String caseId, String vin, Instant now) {
    VehicleEntity entity = vehicles.findByCaseId(caseId)
        .map(existing -> {
          existing.confirmVin(vin, now);
          return existing;
        })
        .orElseGet(() -> new VehicleEntity(UUID.randomUUID().toString(), caseId, vin, now));
    return view(vehicles.save(entity));
  }

  @Transactional
  public VehicleView confirmManual(String caseId, ManualVehicleFacts facts, Instant now) {
    VehicleEntity entity = vehicles.findByCaseId(caseId)
        .map(existing -> {
          existing.confirmManual(facts, now);
          return existing;
        })
        .orElseGet(() -> new VehicleEntity(UUID.randomUUID().toString(), caseId, facts, now));
    return view(vehicles.save(entity));
  }

  public Optional<VehicleView> findByCaseId(String caseId) {
    return vehicles.findByCaseId(caseId).map(this::view);
  }

  /** Seeds a picked listing without inventing manual facts or a VIN. */
  @Transactional
  public VehicleView seedListing(String caseId, ListingSnapshotView listing, Instant now) {
    if (vehicles.findByCaseId(caseId).isPresent()) throw new IllegalStateException("Vehicle evidence already exists");
    var entity = new VehicleEntity(UUID.randomUUID().toString(), caseId, listing, now);
    if (listing.vin() == null && (listing.year() == null || listing.make() == null
        || listing.model() == null || listing.mileage() == null)) entity.confirmationSource = "LISTING_EVIDENCE";
    return view(vehicles.save(entity));
  }

  @Transactional
  public VehicleView confirmListing(String caseId, ListingSnapshotView listing, Instant now) {
    VehicleEntity entity = vehicles.findByCaseId(caseId)
        .orElseThrow(() -> new IllegalStateException("Vehicle evidence is missing"));
    entity.confirmListing(listing, now);
    return view(vehicles.save(entity));
  }

  private VehicleView view(VehicleEntity entity) {
    return new VehicleView(
        entity.vin,
        entity.year,
        entity.make,
        entity.model,
        entity.trim,
        entity.mileage,
        entity.confirmationSource,
        entity.confirmedAt);
  }
}
