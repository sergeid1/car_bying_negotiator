package com.dealanalyzer.extraction;

import java.util.List;

/** Boundary for OCR/layout and schema-constrained extraction providers. */
public interface DealTermsExtractionProvider {
  ProviderExtraction extract(ExtractionDocument document);

  /**
   * Whether the provider meters its own calls. A model-backed provider does, through
   * {@code ModelHarness}, which records the model provenance and token spend; wrapping it in a second
   * meter call would record the call twice.
   */
  default boolean selfMetered() {
    return false;
  }

  record ExtractionDocument(String caseId, String documentId, List<NormalizedDocumentPage> pages) {
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

  /**
   * suggestedChargeId is an optional dealer add-on catalogue id, or a doc fee or state fee id, that a model-backed
   * provider read from the line's printed text (ticket 142). It is shown to the Buyer to confirm and never sets the
   * category on its own; the deterministic provider leaves it empty.
   */
  record LineItem(
      String originalLabel,
      String category,
      long amountMinor,
      String currency,
      String taxClaim,
      String optionalityClaim,
      EvidenceRegion evidence,
      double confidence,
      String suggestedChargeId) {
    public LineItem(String originalLabel, String category, long amountMinor, String currency, String taxClaim,
        String optionalityClaim, EvidenceRegion evidence, double confidence) {
      this(originalLabel, category, amountMinor, currency, taxClaim, optionalityClaim, evidence, confidence, null);
    }
  }

  record EvidenceRegion(
      int pageNumber, double x, double y, double width, double height) {}
}
