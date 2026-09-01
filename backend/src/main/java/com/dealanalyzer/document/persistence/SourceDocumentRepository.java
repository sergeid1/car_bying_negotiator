package com.dealanalyzer.document.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceDocumentRepository extends JpaRepository<SourceDocumentEntity, String> {
  List<SourceDocumentEntity> findByCaseIdOrderByCreatedAt(String caseId);

  List<SourceDocumentEntity> findByLifecycleInAndRetentionDeadlineBeforeOrderByRetentionDeadline(
      List<String> lifecycles, java.time.Instant deadline);
}
