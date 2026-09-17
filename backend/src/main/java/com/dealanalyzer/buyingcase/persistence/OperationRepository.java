package com.dealanalyzer.buyingcase.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface OperationRepository extends JpaRepository<OperationEntity, String> {
  Optional<OperationEntity> findByIdempotencyKey(String idempotencyKey);

  @org.springframework.data.jpa.repository.Query("""
      select operation from OperationEntity operation
      where operation.stage not in :terminalStages and operation.id > :afterId
      order by operation.id
      """)
  List<OperationEntity> findRecoverableAfter(Collection<String> terminalStages, String afterId,
      org.springframework.data.domain.Pageable page);
}
