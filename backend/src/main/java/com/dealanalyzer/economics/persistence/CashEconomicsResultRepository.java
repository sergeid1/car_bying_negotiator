package com.dealanalyzer.economics.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CashEconomicsResultRepository extends JpaRepository<CashEconomicsResultEntity, String> {
  Optional<CashEconomicsResultEntity> findByDealerOfferIdAndPolicyVersionAndEligibilityFingerprint(
      String dealerOfferId, String policyVersion, String eligibilityFingerprint);
  Optional<CashEconomicsResultEntity> findTopByDealerOfferIdOrderByCreatedAtDescIdDesc(String dealerOfferId);
  Optional<CashEconomicsResultEntity> findTopByCaseIdOrderByCreatedAtDescIdDesc(String caseId);
}
