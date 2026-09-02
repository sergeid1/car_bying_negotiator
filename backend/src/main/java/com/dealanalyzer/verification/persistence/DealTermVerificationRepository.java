package com.dealanalyzer.verification.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface DealTermVerificationRepository extends JpaRepository<DealTermVerificationEntity, String> {
  Optional<DealTermVerificationEntity>
      findTopByExtractionIdAndTargetTypeAndTargetIdOrderByVerificationVersionDesc(
          String extractionId, String targetType, String targetId);
}
