package com.dealanalyzer.extraction.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentExtractionRepository extends JpaRepository<DocumentExtractionEntity, String> {
  Optional<DocumentExtractionEntity> findTopByCaseIdOrderByCreatedAtDescIdDesc(String caseId);
  Optional<DocumentExtractionEntity> findTopByDocumentIdOrderByExtractionVersionDesc(String documentId);
  Optional<DocumentExtractionEntity> findByDocumentIdAndExtractionVersion(String documentId, int version);
  List<DocumentExtractionEntity> findByDocumentIdOrderByExtractionVersionDesc(
      String documentId, Pageable pageable);
  @org.springframework.data.jpa.repository.Query(nativeQuery = true,
      value = "select count(*) from source_documents where id = :documentId and case_id = :caseId")
  long countCaseDocument(@org.springframework.data.repository.query.Param("caseId") String caseId,
      @org.springframework.data.repository.query.Param("documentId") String documentId);
}
