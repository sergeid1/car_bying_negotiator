package com.dealanalyzer.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DealReviewRepository extends JpaRepository<DealReviewEntity, String> {
  Optional<DealReviewEntity> findTopByCaseIdOrderByVersionDesc(String caseId);
  Optional<DealReviewEntity> findByCaseIdAndVersion(String caseId, int version);
}
