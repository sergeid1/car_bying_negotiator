package com.dealanalyzer.verification.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DealTermCorrectionRepository extends JpaRepository<DealTermCorrectionEntity, String> {
  Optional<DealTermCorrectionEntity> findByCaseIdAndIdempotencyKey(String caseId, String key);
  Optional<DealTermCorrectionEntity> findTopByExtractionIdAndTargetTypeAndTargetIdOrderByCorrectionVersionDesc(
      String extractionId, String targetType, String targetId);
  List<DealTermCorrectionEntity> findByExtractionIdAndTargetTypeAndTargetIdOrderByCorrectionVersionAsc(
      String extractionId, String targetType, String targetId);
}
