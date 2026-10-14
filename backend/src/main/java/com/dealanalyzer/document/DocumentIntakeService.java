package com.dealanalyzer.document;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.document.DocumentFileInspector.InspectedDocument;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.DocumentOperationView;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.NewDocument;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.RetryDecision;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.UploadRecords;
import com.dealanalyzer.extraction.DocumentExtractionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIntakeService {
  private final DocumentFileInspector inspector;
  private final DocumentObjectStorage objectStorage;
  private final MalwareScanner malwareScanner;
  private final DocumentIntakePersistence persistence;
  private final EphemeralBuyingCaseService buyingCases;
  private final EphemeralCasePersistence casePersistence;
  private final TaskExecutor taskExecutor;
  private final DocumentPageNormalizer pageNormalizer;
  private final DocumentExtractionService extractionService;
  private final com.dealanalyzer.listing.capture.ListingCaptureReader listingReader;
  private final com.dealanalyzer.buyingcase.persistence.CaseRetentionService retentionPolicy;
  private final boolean workerEnabled;

  DocumentIntakeService(
      DocumentFileInspector inspector,
      DocumentObjectStorage objectStorage,
      MalwareScanner malwareScanner,
      DocumentIntakePersistence persistence,
      EphemeralBuyingCaseService buyingCases,
      EphemeralCasePersistence casePersistence,
      @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
      DocumentPageNormalizer pageNormalizer,
      DocumentExtractionService extractionService,
      com.dealanalyzer.listing.capture.ListingCaptureReader listingReader,
      @Value("${document.retention:PT720H}") Duration retention,
      com.dealanalyzer.buyingcase.persistence.CaseRetentionService retentionPolicy,
      @Value("${document.worker.enabled:true}") boolean workerEnabled) {
    if (retention.isNegative() || retention.isZero() || retention.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalArgumentException("Document retention must be between zero and 30 days");
    }
    this.inspector = inspector;
    this.objectStorage = objectStorage;
    this.malwareScanner = malwareScanner;
    this.persistence = persistence;
    this.buyingCases = buyingCases;
    this.casePersistence = casePersistence;
    this.taskExecutor = taskExecutor;
    this.pageNormalizer = pageNormalizer;
    this.extractionService = extractionService;
    this.listingReader = listingReader;
    this.retentionPolicy = retentionPolicy;
    this.workerEnabled = workerEnabled;
  }

  public StartedDocumentUpload upload(
      String caseId,
      String accessToken,
      String idempotencyKey,
      MultipartFile upload) {
    requireServiceProcessingConsent(caseId, accessToken);
    return intake(caseId, accessToken, idempotencyKey, upload.getOriginalFilename(), read(upload));
  }

  /**
   * A dealer attachment received through the email relay. It takes the same inspection, malware scan,
   * consent check and processing pipeline as a Buyer upload; the relay has already authorized the case.
   */
  public StartedDocumentUpload intakeRelayAttachment(
      String caseId, String idempotencyKey, String filename, byte[] bytes) {
    requireServiceProcessingConsent(caseId, null);
    return intake(caseId, null, idempotencyKey, filename, bytes);
  }

  private StartedDocumentUpload intake(
      String caseId, String accessToken, String idempotencyKey, String originalFilename, byte[] bytes) {
    return intake(caseId, accessToken, idempotencyKey, originalFilename, bytes, null);
  }

  /**
   * A written trade offer's photo or PDF. It takes the same inspection, malware scan, consent check,
   * encrypted storage, page normalization and retention as dealer paperwork, but it is never read for
   * Deal Terms and never listed or compared as dealer paperwork. The caller has authorized the case.
   */
  public StartedDocumentUpload intakeTradeOfferEvidence(
      String caseId, String accessToken, String idempotencyKey, MultipartFile upload) {
    requireServiceProcessingConsent(caseId, accessToken);
    return intake(caseId, accessToken, idempotencyKey, upload.getOriginalFilename(), read(upload), null,
        DocumentIntakePersistence.TRADE_OFFER_EVIDENCE);
  }

  /** A normalized page of trade offer evidence, for a caller that has authorized the trade offer. */
  public byte[] tradeOfferEvidencePage(String caseId, String documentId, int pageNumber) {
    if (!persistence.isTradeOfferEvidence(documentId))
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source page not found");
    String objectKey = persistence.normalizedPageKey(caseId, documentId, pageNumber)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source page not found"));
    return objectStorage.readNormalized(caseId, documentId, pageNumber, objectKey);
  }

  /**
   * The Buyer's own capture of a dealer's listing: a screenshot or a saved PDF of the page. It takes the same
   * inspection, malware scan, consent check, encrypted storage and normalization as dealer paperwork, is read
   * only for listing fields, and never changes the case's review status or becomes a Dealer Offer.
   */
  public StartedDocumentUpload intakeListingCapture(
      String caseId, String accessToken, String idempotencyKey, MultipartFile upload) {
    requireServiceProcessingConsent(caseId, accessToken);
    return intake(caseId, accessToken, idempotencyKey, upload.getOriginalFilename(), read(upload), null,
        DocumentIntakePersistence.LISTING_CAPTURE);
  }

  /** Listing text the Buyer pasted, kept as plain text and processed like an uploaded capture. */
  public StartedDocumentUpload intakeListingText(
      String caseId, String accessToken, String idempotencyKey, String text) {
    requireServiceProcessingConsent(caseId, accessToken);
    if (text == null || text.isBlank())
      throw new InvalidDocumentException("EMPTY_TEXT", "Paste the text of the listing first.");
    if (text.length() > 40000)
      throw new InvalidDocumentException("FILE_TOO_LARGE", "The pasted listing text is too long. Paste only the listing itself.");
    String body = text.replace("\r\n", "\n").replace('\r', '\n').strip();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return intake(caseId, accessToken, idempotencyKey, "pasted-listing.txt", bytes,
        new InspectedDocument("text/plain", RelayTextPages.pages(body).size(), null, null, 150, false, false, false, false),
        DocumentIntakePersistence.LISTING_CAPTURE);
  }

  /** A normalized page of a listing capture, for a caller that has authorized the case. */
  public byte[] listingCapturePage(String caseId, String documentId, int pageNumber) {
    if (!persistence.purposeOf(documentId).filter(DocumentIntakePersistence.LISTING_CAPTURE::equals).isPresent())
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source page not found");
    String objectKey = persistence.normalizedPageKey(caseId, documentId, pageNumber)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source page not found"));
    return objectStorage.readNormalized(caseId, documentId, pageNumber, objectKey);
  }

  /** A quoted dealer body, kept as text and routed through the normal extraction/review pipeline. */
  public StartedDocumentUpload intakeRelayBody(String caseId, String messageId, String body) {
    if (body == null || body.isBlank() || body.length() > 40000)
      throw new InvalidDocumentException("FILE_TOO_LARGE", "The dealer quote text is too large.");
    String quoted = "Source: dealer relay message " + messageId + " (untrusted dealer content)\n\n"
        + body.lines().map(line -> "> " + line).collect(java.util.stream.Collectors.joining("\n"));
    byte[] bytes = quoted.getBytes(StandardCharsets.UTF_8);
    return intake(caseId, null, "relay-body:" + messageId, "dealer-reply-" + messageId + ".txt", bytes,
        new InspectedDocument("text/plain", RelayTextPages.pages(quoted).size(), null, null, 150, false, false, false, false));
  }

  private StartedDocumentUpload intake(String caseId, String accessToken, String idempotencyKey,
      String originalFilename, byte[] bytes, InspectedDocument trustedText) {
    return intake(caseId, accessToken, idempotencyKey, originalFilename, bytes, trustedText,
        DocumentIntakePersistence.DEALER_PAPERWORK);
  }

  private StartedDocumentUpload intake(String caseId, String accessToken, String idempotencyKey,
      String originalFilename, byte[] bytes, InspectedDocument trustedText, String purpose) {
    String checksum = sha256(bytes);
    Optional<UploadRecords> existing = persistence.findByIdempotencyKey(caseId, idempotencyKey);
    if (existing.isPresent()) {
      UploadRecords records = existing.orElseThrow();
      if (!records.document().checksumSha256().equals(checksum)) {
        throw new IdempotencyConflictException();
      }
      return started(records);
    }

    InspectedDocument inspected = trustedText == null ? inspector.inspect(bytes) : trustedText;
    malwareScanner.requireClean(bytes);
    requireServiceProcessingConsent(caseId, accessToken);
    String documentId = UUID.randomUUID().toString();
    String operationId = UUID.randomUUID().toString();
    String filename = safeFilename(originalFilename);
    Instant now = Instant.now();
    DocumentObjectStorage.StoredObject stored = objectStorage.store(caseId, documentId, bytes);
    try {
      requireServiceProcessingConsent(caseId, accessToken);
    } catch (RuntimeException revokedBeforePersistence) {
      objectStorage.delete(stored.objectKey());
      throw revokedBeforePersistence;
    }
    UploadRecords records;
    try {
      records = persistence.create(
          new NewDocument(
              documentId,
              caseId,
              filename,
              stored.objectKey(),
              checksum,
              inspected.mediaType(),
              bytes.length,
              retentionPolicy.documentDeadline(caseId, now),
              stored.keyReference(),
              inspected.pageCount(),
              inspected.width(),
              inspected.height(),
              inspected.dpi(),
              inspected.likelyMissing(),
              inspected.likelyDuplicate(),
              inspected.likelyRotated(),
              inspected.likelyCropped()),
          operationId,
          idempotencyKey,
          now,
          purpose);
    } catch (DataIntegrityViolationException duplicate) {
      objectStorage.delete(stored.objectKey());
      records = persistence.findByIdempotencyKey(caseId, idempotencyKey)
          .orElseThrow(() -> duplicate);
      if (!records.document().checksumSha256().equals(checksum)) {
        throw new IdempotencyConflictException();
      }
      return started(records);
    } catch (RuntimeException failure) {
      objectStorage.delete(stored.objectKey());
      throw failure;
    }
    // Trade offer evidence and listing captures are not dealer paperwork, so they leave the review status as it was.
    if (purpose.equals(DocumentIntakePersistence.DEALER_PAPERWORK))
      casePersistence.updateStatus(caseId, "DOCUMENT_PROCESSING", now);
    dispatch(operationId);
    return started(records);
  }

  public Optional<DocumentOperationView> findOperation(
      String operationId, String accessToken) {
    Optional<DocumentOperationView> operation = persistence.findOperation(operationId);
    operation.ifPresent(view -> requireServiceProcessingConsent(view.caseId(), accessToken));
    return operation;
  }

  public SourceDocumentView getDocument(
      String caseId, String documentId, String accessToken) {
    requireServiceProcessingConsent(caseId, accessToken);
    SourceDocumentView document = persistence.requireDocument(documentId);
    if (!document.caseId().equals(caseId)) {
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source Document not found");
    }
    return document;
  }

  public java.util.List<SourceDocumentView> listDocuments(String caseId, String accessToken) {
    requireServiceProcessingConsent(caseId, accessToken);
    return persistence.activeDocuments(caseId);
  }

  /** The normalized PNG the extractor read, so the Buyer can check a term against its source page. */
  public byte[] pageImage(String caseId, String documentId, int pageNumber, String accessToken) {
    requireServiceProcessingConsent(caseId, accessToken);
    String objectKey = persistence.normalizedPageKey(caseId, documentId, pageNumber)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException(
            "Source page not found"));
    return objectStorage.readNormalized(caseId, documentId, pageNumber, objectKey);
  }

  public DocumentOperationView retry(
      String operationId, String accessToken, String idempotencyKey) {
    DocumentOperationView operation = persistence.requireOperation(operationId);
    requireServiceProcessingConsent(operation.caseId(), accessToken);
    RetryDecision retry = persistence.retry(operationId, idempotencyKey, Instant.now());
    if (retry.dispatch()) {
      if (dealerPaperwork(operation.documentId()))
        casePersistence.updateStatus(operation.caseId(), "DOCUMENT_PROCESSING", Instant.now());
      dispatch(operationId);
    }
    return retry.operation();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedOperations() {
    if (workerEnabled) {
      taskExecutor.execute(() -> {
        String afterId = "";
        while (true) {
          var batch = persistence.recoverInterruptedOperations(afterId, Instant.now());
          if (batch.isEmpty()) return;
          for (var operation : batch) {
            try { process(operation.id()); }
            catch (com.dealanalyzer.buyingcase.ResourceNotFoundException deleted) {
              // Concurrent retention deletion must not strand the rest of the recovery backlog.
            }
          }
          afterId = batch.get(batch.size() - 1).id();
        }
      });
    }
  }

  private void dispatch(String operationId) {
    if (workerEnabled) {
      taskExecutor.execute(() -> process(operationId));
    }
  }

  private void process(String operationId) {
    DocumentOperationView operation = persistence.requireOperation(operationId);
    String stage = null;
    try {
      if (operation.stage().equals("QUEUED")) {
        if (!persistence.claimNormalization(operationId, Instant.now())) {
          return;
        }
        stage = "NORMALIZING";
        requireServiceProcessingConsent(operation.caseId(), null);
        pageNormalizer.normalize(persistence.requireProcessingDocument(operation.documentId()));
        persistence.completeNormalization(operationId, Instant.now());
      }
      if (persistence.isTradeOfferEvidence(operation.documentId())) {
        persistence.completeEvidence(operationId, Instant.now());
        return;
      }
      boolean listing = !dealerPaperwork(operation.documentId());
      if (!persistence.claimExtraction(operationId, Instant.now())) {
        return;
      }
      stage = "EXTRACTING";
      requireServiceProcessingConsent(operation.caseId(), null);
      if (listing) {
        // Listing fields only; the case's review status and Dealer Offers are untouched.
        listingReader.read(operation.documentId());
        persistence.completeExtraction(operationId, Instant.now());
        return;
      }
      extractionService.extract(operation.documentId());
      persistence.completeExtraction(operationId, Instant.now());
      casePersistence.updateStatus(operation.caseId(), "NEEDS_REVIEW", Instant.now());
    } catch (Exception exception) {
      if (stage == null) {
        return;
      }
      boolean listing = stage.equals("EXTRACTING") && !dealerPaperwork(operation.documentId());
      persistence.fail(
          operationId, stage,
          exception instanceof com.dealanalyzer.operations.ProviderMeter.Failure providerFailure
              ? providerFailure.status().name()
              : listing ? "LISTING_READ_FAILED" : stage.equals("EXTRACTING") ? "EXTRACTION_FAILED" : "NORMALIZATION_FAILED",
          listing ? "The listing could not be read. Type what the ad shows instead."
              : stage.equals("EXTRACTING")
              ? "Deal Terms extraction could not be completed. Retry this stage without uploading again."
              : "Document page normalization could not be completed. Retry this stage without uploading again.",
          Instant.now());
      if (dealerPaperwork(operation.documentId()))
        casePersistence.updateStatus(operation.caseId(), "FAILED", Instant.now());
    }
  }

  private boolean dealerPaperwork(String documentId) {
    return persistence.purposeOf(documentId).map(DocumentIntakePersistence.DEALER_PAPERWORK::equals).orElse(false);
  }

  private void requireServiceProcessingConsent(String caseId, String accessToken) {
    java.util.List<com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent>
        consents = accessToken == null
            ? casePersistence.findById(caseId).orElseThrow().consents()
            : buyingCases.reopen(caseId, accessToken).consents();
    boolean granted = consents.stream()
        .filter(consent -> consent.purpose()
            == com.dealanalyzer.buyingcase.ChangeConsentRequest.ConsentPurpose.SERVICE_PROCESSING)
        .max(java.util.Comparator.comparingInt(
            com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent::consentVersion))
        .map(consent -> consent.action()
            == com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.ConsentAction.GRANTED)
        .orElse(false);
    if (!granted) {
      throw new InvalidDocumentException(
          "SERVICE_PROCESSING_CONSENT_REQUIRED",
          "Service-processing consent is not currently granted. Grant it before uploading or retrying a document.");
    }
  }

  private static StartedDocumentUpload started(UploadRecords records) {
    return new StartedDocumentUpload(records.document(), records.operation());
  }

  private static byte[] read(MultipartFile upload) {
    try {
      return upload.getBytes();
    } catch (java.io.IOException exception) {
      throw new InvalidDocumentException(
          "UPLOAD_READ_FAILED", "The upload could not be read. Select the document and try again.");
    }
  }

  private static String safeFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      return "dealer-document";
    }
    String normalized = filename.replace('\\', '/');
    String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
        .replaceAll("[\\p{Cntrl}]", "");
    return basename.isBlank() ? "dealer-document" : basename.substring(0, Math.min(255, basename.length()));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record StartedDocumentUpload(
      SourceDocumentView document, DocumentOperationView operation) {}
}
