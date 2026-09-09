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
    this.retentionPolicy = retentionPolicy;
    this.workerEnabled = workerEnabled;
  }

  public StartedDocumentUpload upload(
      String caseId,
      String accessToken,
      String idempotencyKey,
      MultipartFile upload) {
    requireServiceProcessingConsent(caseId, accessToken);
    byte[] bytes = read(upload);
    String checksum = sha256(bytes);
    Optional<UploadRecords> existing = persistence.findByIdempotencyKey(caseId, idempotencyKey);
    if (existing.isPresent()) {
      UploadRecords records = existing.orElseThrow();
      if (!records.document().checksumSha256().equals(checksum)) {
        throw new IdempotencyConflictException();
      }
      return started(records);
    }

    InspectedDocument inspected = inspector.inspect(bytes);
    malwareScanner.requireClean(bytes);
    requireServiceProcessingConsent(caseId, accessToken);
    String documentId = UUID.randomUUID().toString();
    String operationId = UUID.randomUUID().toString();
    String filename = safeFilename(upload.getOriginalFilename());
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
          now);
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

  public DocumentOperationView retry(
      String operationId, String accessToken, String idempotencyKey) {
    DocumentOperationView operation = persistence.requireOperation(operationId);
    requireServiceProcessingConsent(operation.caseId(), accessToken);
    RetryDecision retry = persistence.retry(operationId, idempotencyKey, Instant.now());
    if (retry.dispatch()) {
      casePersistence.updateStatus(operation.caseId(), "DOCUMENT_PROCESSING", Instant.now());
      dispatch(operationId);
    }
    return retry.operation();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedOperations() {
    if (workerEnabled) {
      persistence.recoverInterruptedOperations(Instant.now())
          .forEach(operation -> dispatch(operation.id()));
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
      if (!persistence.claimExtraction(operationId, Instant.now())) {
        return;
      }
      stage = "EXTRACTING";
      requireServiceProcessingConsent(operation.caseId(), null);
      extractionService.extract(operation.documentId());
      persistence.completeExtraction(operationId, Instant.now());
      casePersistence.updateStatus(operation.caseId(), "NEEDS_REVIEW", Instant.now());
    } catch (Exception exception) {
      if (stage == null) {
        return;
      }
      persistence.fail(
          operationId, stage,
          stage.equals("EXTRACTING") ? "EXTRACTION_FAILED" : "NORMALIZATION_FAILED",
          stage.equals("EXTRACTING")
              ? "Deal Terms extraction could not be completed. Retry this stage without uploading again."
              : "Document page normalization could not be completed. Retry this stage without uploading again.",
          Instant.now());
      casePersistence.updateStatus(operation.caseId(), "FAILED", Instant.now());
    }
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
