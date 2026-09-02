package com.dealanalyzer.document.persistence;

import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentIntakePersistence {
  private static final List<String> RETENTION_PENDING = List.of("ACTIVE", "DELETION_IN_PROGRESS");
  private final SourceDocumentRepository documents;
  private final DocumentPageRepository pages;
  private final DocumentOperationRepository operations;
  private final DocumentOperationRetryRepository retries;

  DocumentIntakePersistence(
      SourceDocumentRepository documents,
      DocumentPageRepository pages,
      DocumentOperationRepository operations,
      DocumentOperationRetryRepository retries) {
    this.documents = documents;
    this.pages = pages;
    this.operations = operations;
    this.retries = retries;
  }

  @Transactional
  public UploadRecords create(
      NewDocument document, String operationId, String idempotencyKey, Instant now) {
    SourceDocumentEntity savedDocument = documents.save(new SourceDocumentEntity(
        document.id(), document.caseId(), document.originalFilename(), document.objectKey(),
        document.checksumSha256(), document.verifiedMediaType(), document.sizeBytes(),
        document.retentionDeadline(), document.encryptionKeyReference(), now));
    savedDocument.pageCount = document.pageCount();
    for (int pageNumber = 1; pageNumber <= document.pageCount(); pageNumber++) {
      pages.save(new DocumentPageEntity(
          UUID.randomUUID().toString(), document.id(), pageNumber, document.width(),
          document.height(), document.dpi(), document.likelyMissing(),
          document.likelyDuplicate() && pageNumber > 1, document.likelyRotated(),
          document.likelyCropped(), null, now));
    }
    DocumentOperationEntity savedOperation = operations.saveAndFlush(
        new DocumentOperationEntity(operationId, document.caseId(), document.id(), idempotencyKey, now));
    return new UploadRecords(documentView(savedDocument), operationView(savedOperation));
  }

  @Transactional(readOnly = true)
  public Optional<UploadRecords> findByIdempotencyKey(String caseId, String key) {
    return operations.findByCaseIdAndIdempotencyKey(caseId, key)
        .map(operation -> new UploadRecords(
            documentView(documents.findById(operation.documentId).orElseThrow()),
            operationView(operation)));
  }

  @Transactional(readOnly = true)
  public Optional<DocumentOperationView> findOperation(String operationId) {
    return operations.findById(operationId).map(this::operationView);
  }

  @Transactional(readOnly = true)
  public DocumentOperationView requireOperation(String operationId) {
    return findOperation(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Document operation not found"));
  }

  @Transactional(readOnly = true)
  public SourceDocumentView requireDocument(String documentId) {
    return documents.findById(documentId).map(this::documentView)
        .orElseThrow(() -> new ResourceNotFoundException("Source Document not found"));
  }

  @Transactional(readOnly = true)
  public ProcessingDocument requireProcessingDocument(String documentId) {
    SourceDocumentEntity document = documents.findById(documentId)
        .orElseThrow(() -> new ResourceNotFoundException("Source Document not found"));
    return new ProcessingDocument(
        document.id, document.caseId, document.objectKey, document.verifiedMediaType,
        document.pageCount, document.retentionDeadline, document.lifecycle);
  }

  @Transactional(readOnly = true)
  public ExtractionDocument requireExtractionDocument(String documentId) {
    SourceDocumentEntity document = documents.findById(documentId)
        .orElseThrow(() -> new ResourceNotFoundException("Source Document not found"));
    List<ExtractionPage> extractionPages = pages.findByDocumentIdOrderByPageNumber(documentId)
        .stream()
        .map(page -> new ExtractionPage(
            page.pageNumber, page.normalizedObjectKey, page.likelyMissing,
            page.likelyDuplicate, page.likelyRotated, page.likelyCropped))
        .toList();
    if (!document.lifecycle.equals("ACTIVE")
        || extractionPages.size() != document.pageCount
        || extractionPages.stream().anyMatch(page -> page.normalizedObjectKey() == null)) {
      throw new IllegalStateException("Normalized document is not ready for extraction");
    }
    return new ExtractionDocument(
        document.caseId, document.id, document.objectKey,
        document.verifiedMediaType, extractionPages);
  }

  @Transactional
  public List<DocumentOperationView> recoverInterruptedOperations(Instant now) {
    List<DocumentOperationEntity> recoverable =
        operations.findByStageInOrderByCreatedAt(
            List.of("QUEUED", "NORMALIZING", "READY_FOR_EXTRACTION", "EXTRACTING"));
    recoverable.forEach(operation -> {
      operation.stage = operation.stage.equals("READY_FOR_EXTRACTION")
          || operation.stage.equals("EXTRACTING") ? "READY_FOR_EXTRACTION" : "QUEUED";
      operation.updatedAt = now;
    });
    return recoverable.stream().map(this::operationView).toList();
  }

  @Transactional
  public boolean claimNormalization(String operationId, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (!operation.stage.equals("QUEUED")) {
      return false;
    }
    SourceDocumentEntity document = documents.findById(operation.documentId).orElseThrow();
    if (!document.lifecycle.equals("ACTIVE") || !document.retentionDeadline.isAfter(now)) {
      operation.stage = "FAILED";
      operation.failedStage = "QUEUED";
      operation.errorCode = "DOCUMENT_EXPIRED";
      operation.errorMessage = "The document reached its retention deadline before processing completed.";
      operation.updatedAt = now;
      return false;
    }
    operation.stage = "NORMALIZING";
    operation.updatedAt = now;
    return true;
  }

  @Transactional
  public boolean claimExtraction(String operationId, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (!operation.stage.equals("READY_FOR_EXTRACTION")) {
      return false;
    }
    operation.stage = "EXTRACTING";
    operation.updatedAt = now;
    return true;
  }

  @Transactional
  public void completeExtraction(String operationId, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (operation.stage.equals("EXTRACTING")) {
      operation.stage = "EXTRACTION_COMPLETE";
      operation.failedStage = null;
      operation.errorCode = null;
      operation.errorMessage = null;
      operation.updatedAt = now;
    }
  }

  @Transactional
  public void recordNormalizedPage(
      String documentId, int pageNumber, String objectKey,
      int width, int height, int dpi, Instant now) {
    SourceDocumentEntity document = documents.findById(documentId).orElseThrow();
    if (!document.lifecycle.equals("ACTIVE") || !document.retentionDeadline.isAfter(now)) {
      throw new IllegalStateException("Document expired during normalization");
    }
    DocumentPageEntity page = pages.findByDocumentIdOrderByPageNumber(documentId).stream()
        .filter(candidate -> candidate.pageNumber == pageNumber)
        .findFirst()
        .orElseThrow();
    page.normalizedObjectKey = objectKey;
    page.widthPixels = width;
    page.heightPixels = height;
    page.resolutionDpi = dpi;
    page.normalizedDeletedAt = null;
    document.updatedAt = now;
  }

  @Transactional
  public List<String> clearNormalizedArtifacts(String documentId, Instant now) {
    SourceDocumentEntity document = documents.findById(documentId).orElseThrow();
    List<DocumentPageEntity> documentPages = pages.findByDocumentIdOrderByPageNumber(documentId);
    List<String> keys = documentPages.stream()
        .map(page -> page.normalizedObjectKey)
        .filter(java.util.Objects::nonNull)
        .toList();
    documentPages.forEach(page -> page.normalizedObjectKey = null);
    document.updatedAt = now;
    return keys;
  }

  @Transactional
  public void completeNormalization(String operationId, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (!operation.stage.equals("NORMALIZING")) {
      return;
    }
    SourceDocumentEntity document = documents.findById(operation.documentId).orElseThrow();
    List<DocumentPageEntity> documentPages = pages.findByDocumentIdOrderByPageNumber(document.id);
    if (!document.lifecycle.equals("ACTIVE") || !document.retentionDeadline.isAfter(now)
        || documentPages.size() != document.pageCount
        || documentPages.stream().anyMatch(page -> page.normalizedObjectKey == null)) {
      throw new IllegalStateException("Normalized pages are incomplete or expired");
    }
    document.updatedAt = now;
    operation.stage = "READY_FOR_EXTRACTION";
    operation.failedStage = null;
    operation.errorCode = null;
    operation.errorMessage = null;
    operation.updatedAt = now;
  }

  @Transactional
  public void fail(
      String operationId, String failedStage, String errorCode, String message, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (!operation.stage.equals(failedStage)) {
      return;
    }
    operation.stage = "FAILED";
    operation.failedStage = failedStage;
    operation.errorCode = errorCode;
    operation.errorMessage = message;
    operation.updatedAt = now;
  }

  @Transactional
  public RetryDecision retry(String operationId, String idempotencyKey, Instant now) {
    DocumentOperationEntity operation = requireLockedOperation(operationId);
    if (retries.findByOperationIdAndIdempotencyKey(operationId, idempotencyKey).isPresent()) {
      return new RetryDecision(operationView(operation), false);
    }
    retries.saveAndFlush(new DocumentOperationRetryEntity(
        UUID.randomUUID().toString(), operationId, idempotencyKey, now));
    if (!operation.stage.equals("FAILED")) {
      return new RetryDecision(operationView(operation), false);
    }
    operation.stage = "EXTRACTING".equals(operation.failedStage)
        ? "READY_FOR_EXTRACTION" : "QUEUED";
    operation.errorCode = null;
    operation.errorMessage = null;
    operation.retryCount++;
    operation.updatedAt = now;
    return new RetryDecision(operationView(operation), true);
  }

  @Transactional(readOnly = true)
  public List<RetentionDocument> expiredDocuments(Instant now) {
    return documents
        .findByLifecycleInAndRetentionDeadlineBeforeOrderByRetentionDeadline(RETENTION_PENDING, now)
        .stream()
        .map(document -> new RetentionDocument(
            document.id, document.objectKey, document.originalDeletedAt,
            pages.findByDocumentIdOrderByPageNumber(document.id).stream()
                .map(page -> new RetentionPage(
                    page.id, page.normalizedObjectKey, page.normalizedDeletedAt))
                .toList()))
        .toList();
  }

  @Transactional
  public void markDeletionStarted(String documentId, Instant now) {
    SourceDocumentEntity document = documents.findById(documentId).orElseThrow();
    if (document.lifecycle.equals("ACTIVE")) {
      document.lifecycle = "DELETION_IN_PROGRESS";
      document.updatedAt = now;
    }
  }

  @Transactional
  public void markOriginalDeleted(String documentId, Instant now) {
    SourceDocumentEntity document = documents.findById(documentId).orElseThrow();
    document.originalDeletedAt = now;
    document.updatedAt = now;
  }

  @Transactional
  public void markNormalizedDeleted(String pageId, Instant now) {
    DocumentPageEntity page = pages.findById(pageId).orElseThrow();
    page.normalizedDeletedAt = now;
  }

  @Transactional
  public void completeDeletion(String documentId, Instant now) {
    SourceDocumentEntity document = documents.findById(documentId).orElseThrow();
    boolean pagesDeleted = pages.findByDocumentIdOrderByPageNumber(documentId).stream()
        .allMatch(page -> page.normalizedObjectKey == null || page.normalizedDeletedAt != null);
    if (document.originalDeletedAt == null || !pagesDeleted) {
      throw new IllegalStateException("Document artifacts have not all been deleted");
    }
    document.lifecycle = "DELETED";
    document.updatedAt = now;
  }

  private DocumentOperationEntity requireLockedOperation(String id) {
    return operations.findLockedById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Document operation not found"));
  }

  private SourceDocumentView documentView(SourceDocumentEntity document) {
    List<PageView> pageViews = pages.findByDocumentIdOrderByPageNumber(document.id).stream()
        .map(page -> new PageView(
            page.id, page.pageNumber, page.widthPixels, page.heightPixels, page.resolutionDpi,
            page.likelyMissing, page.likelyDuplicate, page.likelyRotated, page.likelyCropped))
        .toList();
    return new SourceDocumentView(
        document.id, document.caseId, document.originalFilename, document.checksumSha256,
        document.verifiedMediaType, document.sizeBytes, document.pageCount, document.lifecycle,
        document.retentionDeadline,
        new EncryptionView(document.encryptionAlgorithm, document.encryptionKeyReference),
        pageViews, document.createdAt, document.updatedAt);
  }

  private DocumentOperationView operationView(DocumentOperationEntity operation) {
    return new DocumentOperationView(
        operation.id, operation.caseId, operation.documentId, operation.stage,
        operation.failedStage, operation.errorCode, operation.errorMessage,
        operation.retryCount, operation.createdAt, operation.updatedAt);
  }

  public record NewDocument(
      String id, String caseId, String originalFilename, String objectKey,
      String checksumSha256, String verifiedMediaType, long sizeBytes,
      Instant retentionDeadline, String encryptionKeyReference, int pageCount,
      Integer width, Integer height, Integer dpi, boolean likelyMissing,
      boolean likelyDuplicate, boolean likelyRotated, boolean likelyCropped) {}
  public record UploadRecords(SourceDocumentView document, DocumentOperationView operation) {}
  public record SourceDocumentView(
      String id, String caseId, String originalFilename, String checksumSha256,
      String verifiedMediaType, long sizeBytes, Integer pageCount, String lifecycle,
      Instant retentionDeadline, EncryptionView encryption, List<PageView> pages,
      Instant createdAt, Instant updatedAt) {}
  public record EncryptionView(String algorithm, String keyReference) {}
  public record PageView(
      String id, int pageNumber, Integer widthPixels, Integer heightPixels,
      Integer resolutionDpi, boolean likelyMissing, boolean likelyDuplicate,
      boolean likelyRotated, boolean likelyCropped) {}
  public record DocumentOperationView(
      String id, String caseId, String documentId, String stage, String failedStage,
      String errorCode, String error, int retryCount, Instant createdAt, Instant updatedAt) {}
  public record ProcessingDocument(
      String id, String caseId, String objectKey, String mediaType, int pageCount,
      Instant retentionDeadline, String lifecycle) {}
  public record ExtractionDocument(
      String caseId, String documentId, String originalObjectKey,
      String mediaType, List<ExtractionPage> pages) {}
  public record ExtractionPage(
      int pageNumber, String normalizedObjectKey, boolean likelyMissing,
      boolean likelyDuplicate, boolean likelyRotated, boolean likelyCropped) {}
  public record RetryDecision(DocumentOperationView operation, boolean dispatch) {}
  public record RetentionDocument(
      String id, String originalObjectKey, Instant originalDeletedAt, List<RetentionPage> pages) {}
  public record RetentionPage(String id, String objectKey, Instant deletedAt) {}
}
