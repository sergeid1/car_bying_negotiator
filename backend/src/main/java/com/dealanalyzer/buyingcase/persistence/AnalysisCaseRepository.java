package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface AnalysisCaseRepository extends JpaRepository<AnalysisCaseEntity, String> {
  @org.springframework.data.jpa.repository.Query("select c.id from AnalysisCaseEntity c where c.lifecycle = 'ACTIVE' and c.accessExpiresAt <= :now order by c.accessExpiresAt, c.id")
  java.util.List<String> findExpiredIds(java.time.Instant now, org.springframework.data.domain.Pageable page);

  Optional<AnalysisCaseEntity> findByCreationIdempotencyKey(String creationIdempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AnalysisCaseEntity> findLockedById(String id);
}
