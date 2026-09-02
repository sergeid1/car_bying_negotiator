package com.dealanalyzer.verification.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DealerOfferRepository extends JpaRepository<DealerOfferEntity, String> {
  Optional<DealerOfferEntity> findByCaseIdAndIdempotencyKey(String caseId, String key);
  Optional<DealerOfferEntity> findTopByCaseIdOrderByOfferVersionDesc(String caseId);
  Optional<DealerOfferEntity> findByCaseIdAndOfferVersion(String caseId, int version);
}
