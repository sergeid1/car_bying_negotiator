package com.dealanalyzer.vehicle.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleIdentityConflictRepository
    extends JpaRepository<VehicleIdentityConflictEntity, String> {
  List<VehicleIdentityConflictEntity> findByCaseIdOrderByCreatedAt(String caseId);

  Optional<VehicleIdentityConflictEntity> findByIdAndCaseId(String id, String caseId);
}
