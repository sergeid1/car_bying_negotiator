package com.dealanalyzer.document.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentOperationRetryRepository
    extends JpaRepository<DocumentOperationRetryEntity, String> {
  Optional<DocumentOperationRetryEntity> findByOperationIdAndIdempotencyKey(
      String operationId, String idempotencyKey);
}
