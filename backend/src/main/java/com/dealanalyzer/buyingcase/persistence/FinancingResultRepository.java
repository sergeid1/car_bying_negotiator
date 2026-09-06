package com.dealanalyzer.buyingcase.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface FinancingResultRepository extends JpaRepository<FinancingResultEntity, String> {
  Optional<FinancingResultEntity> findByCaseIdAndVersion(String caseId, int version);
}
