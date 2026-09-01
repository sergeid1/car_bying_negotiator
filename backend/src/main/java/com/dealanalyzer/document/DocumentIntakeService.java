package com.dealanalyzer.document;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.document.DocumentFileInspector.InspectedDocument;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.DocumentOperationView;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.NewDocument;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.SourceDocumentView;
import com.dealanalyzer.document.persistence.DocumentIntakePersistence.UploadRecords;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
  private final Duration retention;
  private final boolean workerEnabled;

  DocumentIntakeService(
      DocumentFileInspector inspector,
      DocumentObjectStorage objectStorage,
      MalwareScanner malwareScanner,
      DocumentIntakePersistence persistence,
      EphemeralBuyingCaseService buyingCases,
      EphemeralCasePersistence casePersistence,
      TaskExecutor taskExecutor,
      DocumentPageNormalizer pageNormalizer,
      @Value("${document.retention:PT720H}") Duration retention,
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
    this.retention = retention;
    this.workerEnabled = workerEnabled;
  }

  public StartedDocumentUpload upload(
      String caseId,
      String accessToken,
      String idempotencyKey,
      MultipartFile upload) {
    buyingCases.reopen(caseId, accessToken);
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
    String documentId = UUID.randomUUID().toString();
    String operationId = UUID.randomUUID().toString();
    String filename = safeFilename(upload.getOriginalFilename());
    Instant now = Instant.now();
    DocumentObjectStorage.StoredObject stored = objectStorage.store(caseId, documentId, bytes);
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
              now.plus(retention),
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
    }
    casePersistence.updateStatus(caseId, "DOCUMENT_PROCESSING", now);
    dispatch(operationId);
    return started(records);
  }

  public Optional<DocumentOperationView> findOperation(
      String operationId, String accessToken) {
    Optional<DocumentOperationView> operation = persistence.findOperation(operationId);
    operation.ifPresent(view -> buyingCases.reopen(view.caseId(), accessToken));
    return operation;
  }

  public SourceDocumentView getDocument(
      String caseId, String documentId, String accessToken) {
    buyingCases.reopen(caseId, accessToken);
    SourceDocumentView document = persistence.requireDocument(documentId);
    if (!document.caseId().equals(caseId)) {
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Source Document not found");
    }
    return document;
  }

  public DocumentOperationView retry(
      String operationId, String accessToken, String idempotencyKey) {
    DocumentOperationView operation = persistence.requireOperation(operationId);
    buyingCases.reopen(operation.caseId(), accessToken);
    DocumentOperationView retried = persistence.retry(operationId, Instant.now());
    if (!retried.stage().equals("READY_FOR_EXTRACTION")) {
      casePersistence.updateStatus(operation.caseId(), "DOCUMENT_PROCESSING", Instant.now());
      dispatch(operationId);
    }
    return retried;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverInterruptedOperations() {
    if (workerEnabled) {
      persistence.recoverableOperations().forEach(operation -> dispatch(operation.id()));
    }
  }

  private void dispatch(String operationId) {
    if (workerEnabled) {
      taskExecutor.execute(() -> process(operationId));
    }
  }

  private void process(String operationId) {
    DocumentOperationView operation = persistence.requireOperation(operationId);
    try {
      persistence.markNormalizing(operationId, Instant.now());
      pageNormalizer.normalize(persistence.requireDocument(operation.documentId()));
      persistence.completeNormalization(operationId, Instant.now());
      casePersistence.updateStatus(operation.caseId(), "NEEDS_REVIEW", Instant.now());
    } catch (Exception exception) {
      persistence.fail(
          operationId,
          "NORMALIZING",
          "NORMALIZATION_FAILED",
          "Document page normalization could not be completed. Retry this stage without uploading again.",
          Instant.now());
      casePersistence.updateStatus(operation.caseId(), "FAILED", Instant.now());
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
