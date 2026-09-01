package com.dealanalyzer.document.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentPageRepository extends JpaRepository<DocumentPageEntity, String> {
  List<DocumentPageEntity> findByDocumentIdOrderByPageNumber(String documentId);

  void deleteByDocumentId(String documentId);
}
