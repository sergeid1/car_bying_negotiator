package com.dealanalyzer.vehicle.persistence;

import com.dealanalyzer.vehicle.EstablishVehicleRequest.ManualVehicleFacts;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vehicles")
class VehicleEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, unique = true, length = 36)
  String caseId;

  @Column(length = 17)
  String vin;

  @Column(name = "model_year")
  Integer year;

  @Column(length = 100)
  String make;

  @Column(length = 100)
  String model;

  @Column(name = "trim_name", length = 100)
  String trim;

  Long mileage;

  @Column(name = "confirmation_source", nullable = false, length = 32)
  String confirmationSource;

  @Column(name = "confirmed_at", nullable = false)
  Instant confirmedAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected VehicleEntity() {}

  VehicleEntity(String id, String caseId, String vin, Instant now) {
    this.id = id;
    this.caseId = caseId;
    confirmVin(vin, now);
  }

  void confirmVin(String vin, Instant now) {
    this.vin = vin;
    this.year = null;
    this.make = null;
    this.model = null;
    this.trim = null;
    this.mileage = null;
    this.confirmationSource = "BUYER_VIN";
    this.confirmedAt = now;
    this.updatedAt = now;
  }

  VehicleEntity(String id, String caseId, ManualVehicleFacts facts, Instant now) {
    this.id = id;
    this.caseId = caseId;
    confirmManual(facts, now);
  }

  void confirmManual(ManualVehicleFacts facts, Instant now) {
    this.vin = null;
    this.year = facts.year();
    this.make = facts.make().strip();
    this.model = facts.model().strip();
    this.trim = facts.trim() == null ? null : facts.trim().strip();
    this.mileage = facts.mileage();
    this.confirmationSource = "BUYER_MANUAL";
    this.confirmedAt = now;
    this.updatedAt = now;
  }

  void confirmListing(ListingSnapshotView listing, Instant now) {
    this.vin = listing.vin();
    this.year = listing.year();
    this.make = listing.make();
    this.model = listing.model();
    this.trim = listing.trim();
    this.mileage = listing.mileage();
    this.confirmationSource = "BUYER_LISTING";
    this.confirmedAt = now;
    this.updatedAt = now;
  }
}
