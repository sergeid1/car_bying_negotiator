package com.dealanalyzer.vehicle.persistence;

import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.VehicleEvidenceService.IdentityConflictView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VehicleIdentityConflictPersistence {
  private final VehicleIdentityConflictRepository conflicts;

  VehicleIdentityConflictPersistence(VehicleIdentityConflictRepository conflicts) {
    this.conflicts = conflicts;
  }

  @Transactional
  public IdentityConflictView appendVinMismatch(
      String caseId,
      String listingSnapshotId,
      String confirmedVin,
      String listingVin,
      Instant now) {
    return view(conflicts.save(new VehicleIdentityConflictEntity(
        UUID.randomUUID().toString(),
        caseId,
        listingSnapshotId,
        confirmedVin,
        listingVin,
        now)));
  }

  public List<IdentityConflictView> findByCaseId(String caseId) {
    return conflicts.findByCaseIdOrderByCreatedAt(caseId).stream().map(this::view).toList();
  }

  @Transactional
  public IdentityConflictView keepConfirmed(String caseId, String conflictId, Instant now) {
    VehicleIdentityConflictEntity conflict = unresolved(caseId, conflictId);
    conflict.keepConfirmed(now);
    return view(conflicts.save(conflict));
  }

  @Transactional
  public IdentityConflictView useListing(String caseId, String conflictId, Instant now) {
    VehicleIdentityConflictEntity conflict = unresolved(caseId, conflictId);
    conflict.useListing(now);
    return view(conflicts.save(conflict));
  }

  private VehicleIdentityConflictEntity unresolved(String caseId, String conflictId) {
    VehicleIdentityConflictEntity conflict = conflicts.findByIdAndCaseId(conflictId, caseId)
        .orElseThrow(() -> new InvalidVehicleEvidenceException("Identity conflict was not found."));
    if (!conflict.status.equals("UNRESOLVED")) {
      throw new InvalidVehicleEvidenceException("Identity conflict has already been resolved.");
    }
    return conflict;
  }

  private IdentityConflictView view(VehicleIdentityConflictEntity entity) {
    return new IdentityConflictView(
        entity.id,
        entity.listingSnapshotId,
        entity.field,
        entity.confirmedValue,
        entity.listingValue,
        entity.status,
        entity.createdAt,
        entity.resolvedAt);
  }
}
