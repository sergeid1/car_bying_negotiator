package com.dealanalyzer.document;

import com.dealanalyzer.lifecycle.CaseArtifactErasure;
import org.springframework.stereotype.Component;

@Component
class StoredCaseArtifactErasure implements CaseArtifactErasure {
  private final jakarta.persistence.EntityManager manager;
  private final DocumentObjectStorage storage;
  StoredCaseArtifactErasure(DocumentObjectStorage storage, jakarta.persistence.EntityManager manager) {
    this.storage = storage; this.manager = manager;
  }

  @Override public void eraseAndVerify(String caseId) {
    java.util.UUID.fromString(caseId);
    if (!storage.available()) {
      // A missing root is not proof that previously registered page bytes were removed.
      // Files.exists also returns false on access failure, which must never count as absence.
      storage.caseObjectKeys(caseId);
      long outstanding = ((Number) manager.createNativeQuery(
          "select count(*) from source_documents d where d.case_id = :id and "
              + "(d.original_deleted_at is null or exists (select 1 from document_pages p "
              + "where p.document_id = d.id and p.normalized_object_key is not null "
              + "and p.normalized_deleted_at is null))")
          .setParameter("id", caseId).getSingleResult()).longValue();
      if (outstanding > 0) throw new IllegalStateException("Artifact storage is unavailable");
      return;
    }
    // Prefix inventory includes orphaned originals, normalized pages and interrupted temp writes.
    for (String key : storage.caseObjectKeys(caseId)) storage.delete(key);
    if (!storage.caseObjectKeys(caseId).isEmpty())
      throw new IllegalStateException("Artifact removal not verified");
  }
}
