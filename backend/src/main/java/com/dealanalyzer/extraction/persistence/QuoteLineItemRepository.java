package com.dealanalyzer.extraction.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuoteLineItemRepository extends JpaRepository<QuoteLineItemEntity, String> {
  List<QuoteLineItemEntity> findByExtractionIdOrderById(String extractionId);
}
