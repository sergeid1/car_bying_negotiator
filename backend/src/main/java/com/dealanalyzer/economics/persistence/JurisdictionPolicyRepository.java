package com.dealanalyzer.economics.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface JurisdictionPolicyRepository extends JpaRepository<JurisdictionPolicyEntity, String> {
  Optional<JurisdictionPolicyEntity> findTopByJurisdictionOrderByEffectiveAtDesc(String jurisdiction);
  Optional<JurisdictionPolicyEntity> findTopByJurisdictionAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
      String jurisdiction, java.time.Instant at);
}
