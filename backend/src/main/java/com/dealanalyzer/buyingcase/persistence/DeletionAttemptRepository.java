package com.dealanalyzer.buyingcase.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
interface DeletionAttemptRepository extends JpaRepository<DeletionAttemptEntity, String> {
  java.util.List<DeletionAttemptEntity> findByJobIdOrderByStartedAt(String jobId);
}
