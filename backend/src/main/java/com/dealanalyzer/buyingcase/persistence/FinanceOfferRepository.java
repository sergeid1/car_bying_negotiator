package com.dealanalyzer.buyingcase.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface FinanceOfferRepository extends JpaRepository<FinanceOfferEntity, String> {
  Optional<FinanceOfferEntity> findByCaseIdAndIdempotencyKey(String caseId, String key);
  Optional<FinanceOfferEntity> findTopByCaseIdOrderByVersionDesc(String caseId);
}
