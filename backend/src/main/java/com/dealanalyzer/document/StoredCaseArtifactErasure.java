package com.dealanalyzer.document;

import com.dealanalyzer.lifecycle.CaseArtifactErasure;
import java.nio.file.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class StoredCaseArtifactErasure implements CaseArtifactErasure {
  private final jakarta.persistence.EntityManager manager;
  private final Path root;
  private final DocumentObjectStorage storage;
  StoredCaseArtifactErasure(@Value("${document.storage.path:./var/document-objects}") Path root,
      DocumentObjectStorage storage, jakarta.persistence.EntityManager manager) {
    this.root = root; this.storage = storage; this.manager = manager;
  }

  @Override public void eraseAndVerify(String caseId) {
    java.util.UUID.fromString(caseId);
    if (!Files.exists(root)) {
      // A missing root is not proof that previously registered page bytes were removed.
      // Files.exists also returns false on access failure, which must never count as absence.
      if (!Files.notExists(root)) throw new IllegalStateException("Artifact inventory unavailable");
      long outstanding = ((Number) manager.createNativeQuery(
          "select count(*) from source_documents d where d.case_id = :id and "
              + "(d.original_deleted_at is null or exists (select 1 from document_pages p "
              + "where p.document_id = d.id and p.normalized_object_key is not null "
              + "and p.normalized_deleted_at is null))")
          .setParameter("id", caseId).getSingleResult()).longValue();
      if (outstanding > 0) throw new IllegalStateException("Artifact storage is unavailable");
      return;
    }
    try {
      // Prefix inventory includes orphaned originals, normalized pages and interrupted temp writes.
      try (var files = Files.list(root)) {
        for (var path : files.filter(p -> p.getFileName().toString().startsWith(caseId + "-")).sorted().toList())
          storage.delete(path.getFileName().toString());
      }
      try (var files = Files.list(root)) {
        if (files.anyMatch(p -> p.getFileName().toString().startsWith(caseId + "-")))
          throw new IllegalStateException("Artifact removal not verified");
      }
    } catch (IOException failure) { throw new IllegalStateException("Artifact inventory unavailable", failure); }
  }
}
