package com.dealanalyzer.buyingcase.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutsideFinanceOfferRepository extends JpaRepository<OutsideFinanceOfferEntity, String> {
  Optional<OutsideFinanceOfferEntity> findByCaseIdAndVersion(String caseId, int version);
  Optional<OutsideFinanceOfferEntity> findByCaseIdAndIdempotencyKey(String caseId, String key);
  Optional<OutsideFinanceOfferEntity> findTopByCaseIdOrderByVersionDesc(String caseId);
}
