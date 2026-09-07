package com.dealanalyzer.buyingcase.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface FinancingComparisonRepository extends JpaRepository<FinancingComparisonEntity, String> {
  Optional<FinancingComparisonEntity> findByCaseIdAndVersion(String caseId, int version);

}
