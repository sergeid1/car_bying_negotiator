package com.dealanalyzer.vehicle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vehicle_identity_conflicts")
class VehicleIdentityConflictEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(name = "listing_snapshot_id", nullable = false, length = 36)
  String listingSnapshotId;

  @Column(name = "field_name", nullable = false, length = 40)
  String field;

  @Column(name = "confirmed_value", nullable = false, length = 200)
  String confirmedValue;

  @Column(name = "listing_value", nullable = false, length = 200)
  String listingValue;

  @Column(nullable = false, length = 40)
  String status;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "resolved_at")
  Instant resolvedAt;

  protected VehicleIdentityConflictEntity() {}

  VehicleIdentityConflictEntity(
      String id,
      String caseId,
      String listingSnapshotId,
      String confirmedValue,
      String listingValue,
      Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.listingSnapshotId = listingSnapshotId;
    this.field = "VIN";
    this.confirmedValue = confirmedValue;
    this.listingValue = listingValue;
    this.status = "UNRESOLVED";
    this.createdAt = createdAt;
  }

  void keepConfirmed(Instant now) {
    status = "RESOLVED_KEEP_CONFIRMED";
    resolvedAt = now;
  }

  void useListing(Instant now) {
    status = "RESOLVED_USE_LISTING";
    resolvedAt = now;
  }
}
