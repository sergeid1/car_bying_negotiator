package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface AnalysisCaseRepository extends JpaRepository<AnalysisCaseEntity, String> {
  Optional<AnalysisCaseEntity> findByCreationIdempotencyKey(String creationIdempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalysisCaseEntity> findLockedById(String id);
}
