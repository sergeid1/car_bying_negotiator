package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.RetentionDocument;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DocumentRetentionService {
  private final DocumentIntakePersistence persistence;
  private final DocumentObjectStorage storage;

  DocumentRetentionService(
      DocumentIntakePersistence persistence, DocumentObjectStorage storage) {
    this.persistence = persistence;
    this.storage = storage;
  }

  @Scheduled(fixedDelayString = "${document.retention-cleanup-delay:PT5M}")
  public void deleteExpiredArtifacts() {
    Instant now = Instant.now();
    for (RetentionDocument document : persistence.expiredDocuments(now)) {
      try {
        delete(document, now);
      } catch (RuntimeException ignored) {
        // Persisted per-artifact progress makes the next scheduled pass safe to retry.
      }
    }
  }

  private void delete(RetentionDocument document, Instant now) {
    persistence.markDeletionStarted(document.id(), now);
    if (document.originalDeletedAt() == null) {
      storage.delete(document.originalObjectKey());
      persistence.markOriginalDeleted(document.id(), Instant.now());
    }
    for (var page : document.pages()) {
      if (page.objectKey() != null && page.deletedAt() == null) {
        storage.delete(page.objectKey());
        persistence.markNormalizedDeleted(page.id(), Instant.now());
      }
    }
    persistence.completeDeletion(document.id(), Instant.now());
  }
}
