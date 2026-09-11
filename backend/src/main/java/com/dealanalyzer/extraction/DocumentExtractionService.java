package com.dealanalyzer.extraction;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.ExtractionDocument;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionSummary;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentExtractionService {
  private final DealTermsExtractionProvider provider;
  private final DocumentExtractionSource source;
  private final DocumentExtractionPersistence persistence;
  private final EphemeralBuyingCaseService cases;
  private final DealTermsValidator validator;
  private final com.dealanalyzer.operations.ProviderMeter meter;

  DocumentExtractionService(
      DealTermsExtractionProvider provider,
      DocumentExtractionSource source,
      DocumentExtractionPersistence persistence,
      EphemeralBuyingCaseService cases,
      @Value("${extraction.material-confidence-threshold:0.85}") double threshold, com.dealanalyzer.operations.ProviderMeter meter) {
    this.meter = meter;
    this.provider = provider;
    this.source = source;
    this.persistence = persistence;
    this.cases = cases;
    this.validator = new DealTermsValidator(threshold);
  }

  public ExtractionView extract(String documentId) {
    DocumentExtractionSource.ExtractionSourceDocument document = source.load(documentId);
    boolean pageWarning = document.pages().stream().anyMatch(page ->
        page.likelyMissing() || page.likelyDuplicate() || page.likelyCropped());
    var providerResult = meter.call(document.caseId(), com.dealanalyzer.operations.ProviderMeter.Operation.EXTRACTION,
        provider, document.pages().size(), () -> provider.extract(new ExtractionDocument(documentId, document.pages())), value -> {
          validator.validate(value, document.pages().size(), pageWarning);
          return com.dealanalyzer.operations.ProviderMeter.Status.SUCCESS;
        });
    DealTermsValidator.ValidatedExtraction validated = validator.validate(providerResult, document.pages().size(), pageWarning);
    return persistence.append(
        document.caseId(), documentId,
        providerResult.extractorVersion(), providerResult.modelVersion(),
        providerResult.schemaVersion(), validated, Instant.now());
  }

  public ExtractionView latest(String caseId, String documentId, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.requireLatest(caseId, documentId);
  }

  public ExtractionView version(
      String caseId, String documentId, int version, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.requireVersion(caseId, documentId, version);
  }

  public List<ExtractionSummary> history(
      String caseId, String documentId, int limit, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.history(caseId, documentId, limit);
  }
}
