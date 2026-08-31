package com.dealanalyzer.vehicle.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleRepository extends JpaRepository<VehicleEntity, String> {
  Optional<VehicleEntity> findByCaseId(String caseId);
}
