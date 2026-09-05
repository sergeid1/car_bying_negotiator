package com.dealanalyzer.market.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface MarketAssessmentRepository extends JpaRepository<MarketAssessmentEntity, String> {
  @EntityGraph(attributePaths = "providerSnapshot")
  Optional<MarketAssessmentEntity> findTopByCaseIdOrderByVersionDesc(String caseId);

  @EntityGraph(attributePaths = "providerSnapshot")
  Optional<MarketAssessmentEntity> findByCaseIdAndVersion(String caseId, int version);
}
