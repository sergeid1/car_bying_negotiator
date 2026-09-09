package com.dealanalyzer.document;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
@Service
public class RetainedDocumentExport {
  private final EntityManager manager;
  private final DocumentObjectStorage storage;
  public RetainedDocumentExport(EntityManager manager, DocumentObjectStorage storage) { this.manager=manager; this.storage=storage; }
  public record RetainedFile(String path, byte[] content) {}
  public List<RetainedFile> read(String caseId) {
    List<RetainedFile> files=new ArrayList<>();
    List<?> rows=manager.createNativeQuery("select id, object_key from source_documents where case_id = :id "
        + "and original_deleted_at is null and lifecycle = 'ACTIVE' and retention_deadline > :now order by id")
        .setParameter("id",caseId).setParameter("now",Instant.now()).getResultList();
    for(Object value:rows) { var row=(Object[])value;
      files.add(new RetainedFile(row[0].toString(),storage.readOriginal(caseId,row[0].toString(),row[1].toString())));
    }
    List<?> pages=manager.createNativeQuery("select p.document_id, p.page_number, p.normalized_object_key from document_pages p "
        + "join source_documents d on d.id=p.document_id where d.case_id=:id and d.lifecycle='ACTIVE' "
        + "and d.retention_deadline > :now and p.normalized_deleted_at is null and p.normalized_object_key is not null order by p.document_id,p.page_number")
        .setParameter("id",caseId).setParameter("now",Instant.now()).getResultList();
    for(Object value:pages) { var row=(Object[])value; int page=((Number)row[1]).intValue();
      files.add(new RetainedFile(row[0]+"-page-"+page+".png",storage.readNormalized(caseId,row[0].toString(),page,row[2].toString())));
    }
    return files;
  }
  public Instant earliestRetentionDeadline(String caseId, Instant fallback) {
    List<?> values=manager.createNativeQuery("select retention_deadline from source_documents where case_id=:id "
        + "and lifecycle='ACTIVE' and retention_deadline > :now order by retention_deadline")
        .setParameter("id",caseId).setParameter("now",Instant.now()).setMaxResults(1).getResultList();
    if(values.isEmpty()) return fallback;
    Object value=values.getFirst();
    Instant deadline=value instanceof java.time.OffsetDateTime time ? time.toInstant()
        : value instanceof Instant time ? time : ((java.sql.Timestamp)value).toInstant();
    return deadline.isBefore(fallback) ? deadline : fallback;
  }
}
