package com.dealanalyzer.extraction.persistence;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion;
import com.dealanalyzer.extraction.DealTermsValidator.ValidatedExtraction;
import com.dealanalyzer.extraction.DealTermsValidator.ValidationIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentExtractionPersistence {
  private final DocumentExtractionRepository extractions;
  private final CandidateDealTermRepository candidates;
  private final QuoteLineItemRepository lineItems;
  private final ExtractionValidationIssueRepository issues;
  private final ObjectMapper json;

  DocumentExtractionPersistence(
      DocumentExtractionRepository extractions,
      CandidateDealTermRepository candidates,
      QuoteLineItemRepository lineItems,
      ExtractionValidationIssueRepository issues,
      ObjectMapper json) {
    this.extractions = extractions;
    this.candidates = candidates;
    this.lineItems = lineItems;
    this.issues = issues;
    this.json = json;
  }

  @Transactional
  public ExtractionView append(
      String caseId,
      String documentId,
      String extractorVersion,
      String modelVersion,
      String schemaVersion,
      ValidatedExtraction validated,
      Instant now) {
    int version = extractions.findTopByDocumentIdOrderByExtractionVersionDesc(documentId)
        .map(entity -> entity.extractionVersion + 1).orElse(1);
    String extractionId = UUID.randomUUID().toString();
    DocumentExtractionEntity extraction = extractions.saveAndFlush(new DocumentExtractionEntity(
        extractionId, documentId, caseId, version, validated.status(),
        extractorVersion, modelVersion, schemaVersion,
        BigDecimal.valueOf(validated.overallConfidence()), validated.suspectedMissingPages(),
        writeWarnings(validated.documentWarnings()), now));
    candidates.saveAll(validated.candidates().stream()
        .map(value -> new CandidateDealTermEntity(
            UUID.randomUUID().toString(), extractionId, value, now))
        .toList());
    lineItems.saveAll(validated.lineItems().stream()
        .map(value -> new QuoteLineItemEntity(
            UUID.randomUUID().toString(), extractionId, value, now))
        .toList());
    issues.saveAll(validated.issues().stream()
        .map(value -> new ExtractionValidationIssueEntity(
            UUID.randomUUID().toString(), extractionId, value, now))
        .toList());
    return view(extraction);
  }

  @Transactional(readOnly = true)
  public ExtractionView requireLatest(String caseId, String documentId) {
    DocumentExtractionEntity extraction = extractions
        .findTopByDocumentIdOrderByExtractionVersionDesc(documentId)
        .filter(value -> value.caseId.equals(caseId))
        .orElseThrow(() -> new ResourceNotFoundException("Document Extraction not found"));
    return view(extraction);
  }

  @Transactional(readOnly = true)
  public ExtractionView requireVersion(String caseId, String documentId, int version) {
    DocumentExtractionEntity extraction = extractions
        .findByDocumentIdAndExtractionVersion(documentId, version)
        .filter(value -> value.caseId.equals(caseId))
        .orElseThrow(() -> new ResourceNotFoundException("Document Extraction not found"));
    return view(extraction);
  }

  /** Returns only summary rows, with an enforced upper bound, and never fetches child collections. */
  @Transactional(readOnly = true)
  public List<ExtractionSummary> history(String caseId, String documentId, int limit) {
    int bounded = Math.max(1, Math.min(limit, 20));
    return extractions.findByDocumentIdOrderByExtractionVersionDesc(
            documentId, PageRequest.of(0, bounded)).stream()
        .filter(value -> value.caseId.equals(caseId))
        .map(value -> new ExtractionSummary(
            value.id, value.extractionVersion, value.status,
            value.overallConfidence.doubleValue(), value.createdAt))
        .toList();
  }

  private ExtractionView view(DocumentExtractionEntity extraction) {
    List<CandidateView> candidateViews = candidates
        .findByExtractionIdOrderByFieldKeyAscIdAsc(extraction.id).stream()
        .map(value -> new CandidateView(
            value.id, value.fieldKey, value.originalWording, value.normalizedValue,
            value.normalizedUnit,
            evidence(value.pageNumber, value.regionX, value.regionY, value.regionWidth, value.regionHeight),
            value.confidence.doubleValue(), value.material, value.verificationState))
        .toList();
    List<LineItemView> lineItemViews = lineItems.findByExtractionIdOrderById(extraction.id).stream()
        .map(value -> new LineItemView(
            value.id, value.originalLabel, value.normalizedCategory, value.amountMinor,
            value.currency, value.taxClaim, value.optionalityClaim,
            evidence(value.pageNumber, value.regionX, value.regionY, value.regionWidth, value.regionHeight),
            value.confidence.doubleValue(), value.verificationState))
        .toList();
    List<ValidationIssue> issueViews = issues.findByExtractionIdOrderById(extraction.id).stream()
        .map(value -> new ValidationIssue(
            value.severity, value.issueCode, value.fieldKey, value.message))
        .toList();
    return new ExtractionView(
        extraction.id, extraction.caseId, extraction.documentId, extraction.extractionVersion,
        extraction.status, extraction.extractorVersion, extraction.modelVersion,
        extraction.schemaVersion, extraction.overallConfidence.doubleValue(),
        extraction.suspectedMissingPages, readWarnings(extraction.documentWarnings),
        candidateViews, lineItemViews, issueViews, extraction.createdAt);
  }

  private static EvidenceRegion evidence(
      int page, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
    return new EvidenceRegion(
        page, x.doubleValue(), y.doubleValue(), width.doubleValue(), height.doubleValue());
  }

  private String writeWarnings(List<String> warnings) {
    try {
      return json.writeValueAsString(warnings);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to persist extraction warnings", exception);
    }
  }

  private List<String> readWarnings(String warnings) {
    try {
      return Arrays.asList(json.readValue(warnings, String[].class));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read extraction warnings", exception);
    }
  }

  public record ExtractionSummary(
      String id, int version, String status, double overallConfidence, Instant createdAt) {}
  public record CandidateView(
      String id, String field, String originalWording, String normalizedValue,
      String normalizedUnit, EvidenceRegion evidence, double confidence,
      boolean material, String verificationState) {}
  public record LineItemView(
      String id, String originalLabel, String category, long amountMinor, String currency,
      String taxClaim, String optionalityClaim, EvidenceRegion evidence, double confidence,
      String verificationState) {}
  public record ExtractionView(
      String id, String caseId, String documentId, int version, String status,
      String extractorVersion, String modelVersion, String schemaVersion,
      double overallConfidence, boolean suspectedMissingPages, List<String> documentWarnings,
      List<CandidateView> candidates, List<LineItemView> lineItems,
      List<ValidationIssue> validationIssues, Instant createdAt) {}
}
