package com.dealanalyzer.buyingcase.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentRecordRepository extends JpaRepository<ConsentRecordEntity, String> {
  List<ConsentRecordEntity> findByCaseId(String caseId);

  Optional<ConsentRecordEntity> findTopByCaseIdAndPurposeOrderByConsentVersionDesc(
      String caseId, String purpose);
}
