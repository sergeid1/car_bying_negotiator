package com.dealanalyzer.buyingcase.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface OperationRepository extends JpaRepository<OperationEntity, String> {
  Optional<OperationEntity> findByIdempotencyKey(String idempotencyKey);

  List<OperationEntity> findByStageNotInOrderByCreatedAt(Collection<String> terminalStages);
}
