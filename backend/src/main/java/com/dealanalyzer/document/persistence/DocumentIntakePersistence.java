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
  private static final List<String> RECOVERABLE = List.of("QUEUED", "NORMALIZING");

  private final SourceDocumentRepository documents;
  private final DocumentPageRepository pages;
  private final DocumentOperationRepository operations;

  DocumentIntakePersistence(
      SourceDocumentRepository documents,
      DocumentPageRepository pages,
      DocumentOperationRepository operations) {
    this.documents = documents;
    this.pages = pages;
    this.operations = operations;
  }

  @Transactional
  public UploadRecords create(
      NewDocument document, String operationId, String idempotencyKey, Instant now) {
    SourceDocumentEntity savedDocument = documents.save(new SourceDocumentEntity(
        document.id(),
        document.caseId(),
        document.originalFilename(),
        document.objectKey(),
        document.checksumSha256(),
        document.verifiedMediaType(),
        document.sizeBytes(),
        document.retentionDeadline(),
        document.encryptionKeyReference(),
        now));
    savedDocument.pageCount = document.pageCount();
    for (int pageNumber = 1; pageNumber <= document.pageCount(); pageNumber++) {
      pages.save(new DocumentPageEntity(
          UUID.randomUUID().toString(),
          document.id(),
          pageNumber,
          document.width(),
          document.height(),
          document.dpi(),
          document.likelyMissing(),
          document.likelyDuplicate() && pageNumber > 1,
          document.likelyRotated(),
          document.likelyCropped(),
          null,
          now));
    }
    DocumentOperationEntity savedOperation = operations.saveAndFlush(
        new DocumentOperationEntity(
            operationId, document.caseId(), document.id(), idempotencyKey, now));
    return new UploadRecords(view(savedDocument), view(savedOperation));
  }

  @Transactional(readOnly = true)
  public Optional<UploadRecords> findByIdempotencyKey(String caseId, String key) {
    return operations.findByCaseIdAndIdempotencyKey(caseId, key)
        .map(operation -> new UploadRecords(
            documentView(documents.findById(operation.documentId).orElseThrow()),
            view(operation)));
  }

  @Transactional(readOnly = true)
  public Optional<DocumentOperationView> findOperation(String operationId) {
    return operations.findById(operationId).map(this::view);
  }

  @Transactional(readOnly = true)
  public DocumentOperationView requireOperation(String operationId) {
    return findOperation(operationId)
        .orElseThrow(() -> new ResourceNotFoundException("Document operation not found"));
  }

  @Transactional(readOnly = true)
  public SourceDocumentView requireDocument(String documentId) {
    return documents.findById(documentId)
        .map(this::documentView)
        .orElseThrow(() -> new ResourceNotFoundException("Source Document not found"));
  }

  @Transactional(readOnly = true)
  public List<DocumentOperationView> recoverableOperations() {
    return operations.findByStageInOrderByCreatedAt(RECOVERABLE).stream().map(this::view).toList();
  }

  @Transactional
  public void markNormalizing(String operationId, Instant now) {
    DocumentOperationEntity operation = requireOperationEntity(operationId);
    operation.stage = "NORMALIZING";
    operation.updatedAt = now;
  }

  @Transactional
  public void completeNormalization(String operationId, Instant now) {
    DocumentOperationEntity operation = requireOperationEntity(operationId);
    SourceDocumentEntity document = documents.findById(operation.documentId).orElseThrow();
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
    DocumentOperationEntity operation = requireOperationEntity(operationId);
    operation.stage = "FAILED";
    operation.failedStage = failedStage;
    operation.errorCode = errorCode;
    operation.errorMessage = message;
    operation.updatedAt = now;
  }

  @Transactional
  public DocumentOperationView retry(String operationId, Instant now) {
    DocumentOperationEntity operation = requireOperationEntity(operationId);
    if (!operation.stage.equals("FAILED")) {
      return view(operation);
    }
    operation.stage = operation.failedStage == null ? "QUEUED" : operation.failedStage;
    operation.errorCode = null;
    operation.errorMessage = null;
    operation.retryCount++;
    operation.updatedAt = now;
    return view(operation);
  }

  private DocumentOperationEntity requireOperationEntity(String id) {
    return operations.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Document operation not found"));
  }

  private SourceDocumentView documentView(SourceDocumentEntity document) {
    List<PageView> pageViews = pages.findByDocumentIdOrderByPageNumber(document.id).stream()
        .map(page -> new PageView(
            page.id,
            page.pageNumber,
            page.widthPixels,
            page.heightPixels,
            page.resolutionDpi,
            page.likelyMissing,
            page.likelyDuplicate,
            page.likelyRotated,
            page.likelyCropped))
        .toList();
    return new SourceDocumentView(
        document.id,
        document.caseId,
        document.originalFilename,
        document.checksumSha256,
        document.verifiedMediaType,
        document.sizeBytes,
        document.pageCount,
        document.lifecycle,
        document.retentionDeadline,
        new EncryptionView(document.encryptionAlgorithm, document.encryptionKeyReference),
        pageViews,
        document.createdAt,
        document.updatedAt);
  }

  private SourceDocumentView view(SourceDocumentEntity document) {
    return documentView(document);
  }

  private DocumentOperationView view(DocumentOperationEntity operation) {
    return new DocumentOperationView(
        operation.id,
        operation.caseId,
        operation.documentId,
        operation.stage,
        operation.failedStage,
        operation.errorCode,
        operation.errorMessage,
        operation.retryCount,
        operation.createdAt,
        operation.updatedAt);
  }

  public record NewDocument(
      String id,
      String caseId,
      String originalFilename,
      String objectKey,
      String checksumSha256,
      String verifiedMediaType,
      long sizeBytes,
      Instant retentionDeadline,
      String encryptionKeyReference,
      int pageCount,
      Integer width,
      Integer height,
      Integer dpi,
      boolean likelyMissing,
      boolean likelyDuplicate,
      boolean likelyRotated,
      boolean likelyCropped) {}

  public record UploadRecords(SourceDocumentView document, DocumentOperationView operation) {}

  public record SourceDocumentView(
      String id,
      String caseId,
      String originalFilename,
      String checksumSha256,
      String verifiedMediaType,
      long sizeBytes,
      Integer pageCount,
      String lifecycle,
      Instant retentionDeadline,
      EncryptionView encryption,
      List<PageView> pages,
      Instant createdAt,
      Instant updatedAt) {}

  public record EncryptionView(String algorithm, String keyReference) {}

  public record PageView(
      String id,
      int pageNumber,
      Integer widthPixels,
      Integer heightPixels,
      Integer resolutionDpi,
      boolean likelyMissing,
      boolean likelyDuplicate,
      boolean likelyRotated,
      boolean likelyCropped) {}

  public record DocumentOperationView(
      String id,
      String caseId,
      String documentId,
      String stage,
      String failedStage,
      String errorCode,
      String error,
      int retryCount,
      Instant createdAt,
      Instant updatedAt) {}
}
