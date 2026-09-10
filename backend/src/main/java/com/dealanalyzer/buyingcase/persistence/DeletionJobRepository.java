package com.dealanalyzer.buyingcase.persistence;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface DeletionJobRepository extends JpaRepository<DeletionJobEntity, String> {
  Optional<DeletionJobEntity> findByCaseId(String caseId);
  List<DeletionJobEntity> findByStateOrderByRequestedAt(String state);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<DeletionJobEntity> findLockedById(String id);
}
