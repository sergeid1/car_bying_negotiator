package com.dealanalyzer.document.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

interface DocumentOperationRepository extends JpaRepository<DocumentOperationEntity, String> {
  Optional<DocumentOperationEntity> findByCaseIdAndIdempotencyKey(
      String caseId, String idempotencyKey);

  @org.springframework.data.jpa.repository.Query("""
      select operation from DocumentOperationEntity operation
      where operation.stage in :stages and operation.id > :afterId
      order by operation.id
      """)
  List<DocumentOperationEntity> findRecoverableAfter(List<String> stages, String afterId,
      org.springframework.data.domain.Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select operation from DocumentOperationEntity operation where operation.id = :id")
  Optional<DocumentOperationEntity> findLockedById(String id);
}
