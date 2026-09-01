package com.dealanalyzer.document.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentOperationRepository extends JpaRepository<DocumentOperationEntity, String> {
  Optional<DocumentOperationEntity> findByCaseIdAndIdempotencyKey(
      String caseId, String idempotencyKey);

  List<DocumentOperationEntity> findByStageInOrderByCreatedAt(List<String> stages);
}
