package com.dealanalyzer.extraction.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CandidateDealTermRepository extends JpaRepository<CandidateDealTermEntity, String> {
  List<CandidateDealTermEntity> findByExtractionIdOrderByFieldKeyAscIdAsc(String extractionId);
}
