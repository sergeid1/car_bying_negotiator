package com.dealanalyzer.listing.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DealerRepository extends JpaRepository<DealerEntity, String> {
  Optional<DealerEntity> findByCaseIdAndSourceAndProviderDealerId(
      String caseId, String source, String providerDealerId);
}
