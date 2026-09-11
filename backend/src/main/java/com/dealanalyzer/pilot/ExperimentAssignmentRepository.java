package com.dealanalyzer.pilot;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignmentEntity, String> {
  Optional<ExperimentAssignmentEntity> findByCaseId(String caseId);
}
