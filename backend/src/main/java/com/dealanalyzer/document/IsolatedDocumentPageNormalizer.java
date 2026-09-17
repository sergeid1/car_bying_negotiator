package com.dealanalyzer.document;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.ProcessingDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class IsolatedDocumentPageNormalizer implements DocumentPageNormalizer {
  private final DocumentObjectStorage storage;
  private final DocumentIntakePersistence persistence;
  private final DocumentParserProcess parser;

  IsolatedDocumentPageNormalizer(DocumentObjectStorage storage,
      DocumentIntakePersistence persistence, DocumentParserProcess parser) {
    this.storage = storage; this.persistence = persistence; this.parser = parser;
  }

  @Override
  public void normalize(ProcessingDocument document) {
    if (!document.lifecycle().equals("ACTIVE")
        || !document.retentionDeadline().isAfter(Instant.now())) {
      throw new IllegalStateException("Document is unavailable for normalization");
    }
    deleteKnownOutputs(document.id());
    List<String> written = new ArrayList<>();
    try {
      byte[] original = storage.readOriginal(document.caseId(), document.id(), document.objectKey());
      for (int number = 1; number <= document.pageCount(); number++) {
        var page = parser.render(original, number);
        var stored = storage.storeNormalized(document.caseId(), document.id(), number, page.png());
        written.add(stored.objectKey());
        persistence.recordNormalizedPage(document.id(), number, stored.objectKey(),
            page.width(), page.height(), page.dpi(), Instant.now());
      }
      if (written.size() != document.pageCount()) {
        throw new IllegalStateException("Normalizer produced an incomplete page set");
      }
    } catch (Exception exception) {
      written.addAll(persistence.clearNormalizedArtifacts(document.id(), Instant.now()));
      written.stream().distinct().forEach(this::deleteQuietly);
      throw exception instanceof RuntimeException runtime
          ? runtime : new IllegalStateException("Document normalization failed", exception);
    }
  }

  private void deleteKnownOutputs(String documentId) {
    persistence.clearNormalizedArtifacts(documentId, Instant.now()).forEach(this::deleteQuietly);
  }

  private void deleteQuietly(String objectKey) {
    try {
      storage.delete(objectKey);
    } catch (RuntimeException ignored) {
      // The failed operation remains retryable and retention cleanup retries deletion.
    }
  }
}
