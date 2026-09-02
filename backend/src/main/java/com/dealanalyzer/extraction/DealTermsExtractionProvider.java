package com.dealanalyzer.extraction;

import java.util.List;

/** Boundary for OCR/layout and schema-constrained extraction providers. */
public interface DealTermsExtractionProvider {
  ProviderExtraction extract(ExtractionDocument document);

  record ExtractionDocument(String documentId, List<NormalizedDocumentPage> pages) {
    public ExtractionDocument {
      pages = List.copyOf(pages);
    }
  }

  record ProviderExtraction(
      String extractorVersion,
      String modelVersion,
      String schemaVersion,
      Integer expectedPageCount,
      List<Candidate> candidates,
      List<LineItem> lineItems,
      List<String> documentWarnings) {
    public ProviderExtraction {
      candidates = candidates == null ? List.of() : List.copyOf(candidates);
      lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
      documentWarnings = documentWarnings == null ? List.of() : List.copyOf(documentWarnings);
    }
  }

  record Candidate(
      String field,
      String originalWording,
      String normalizedValue,
      String normalizedUnit,
      EvidenceRegion evidence,
      double confidence,
      boolean material) {}

  record LineItem(
      String originalLabel,
      String category,
      long amountMinor,
      String currency,
      String taxClaim,
      String optionalityClaim,
      EvidenceRegion evidence,
      double confidence) {}

  record EvidenceRegion(
      int pageNumber, double x, double y, double width, double height) {}
}
