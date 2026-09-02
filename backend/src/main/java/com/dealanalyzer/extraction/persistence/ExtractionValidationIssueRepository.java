package com.dealanalyzer.extraction.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExtractionValidationIssueRepository extends JpaRepository<ExtractionValidationIssueEntity, String> {
  List<ExtractionValidationIssueEntity> findByExtractionIdOrderById(String extractionId);
}
