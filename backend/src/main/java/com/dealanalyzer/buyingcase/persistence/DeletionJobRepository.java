package com.dealanalyzer.buyingcase.persistence;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface DeletionJobRepository extends JpaRepository<DeletionJobEntity, String> {
  Optional<DeletionJobEntity> findByCaseId(String caseId);
  @Query("select j from DeletionJobEntity j where j.state = :state order by coalesce(j.lastAttemptAt, j.requestedAt), j.id")
  List<DeletionJobEntity> findPendingBatch(String state, org.springframework.data.domain.Pageable page);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DeletionJobEntity> findLockedById(String id);
}
