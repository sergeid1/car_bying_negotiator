package com.dealanalyzer.document.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "source_documents")
class SourceDocumentEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(name = "original_filename", nullable = false, length = 255)
  String originalFilename;

  @Column(name = "object_key", nullable = false, unique = true, length = 255)
  String objectKey;

  @Column(name = "checksum_sha256", nullable = false, length = 64)
  String checksumSha256;

  @Column(name = "verified_media_type", nullable = false, length = 40)
  String verifiedMediaType;

  @Column(name = "size_bytes", nullable = false)
  long sizeBytes;

  @Column(name = "page_count")
  Integer pageCount;

  @Column(nullable = false, length = 32)
  String lifecycle;

  @Column(name = "retention_deadline", nullable = false)
  Instant retentionDeadline;

  @Column(name = "encryption_algorithm", nullable = false, length = 32)
  String encryptionAlgorithm;

  @Column(name = "encryption_key_reference", nullable = false, length = 128)
  String encryptionKeyReference;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "original_deleted_at")
  Instant originalDeletedAt;

  protected SourceDocumentEntity() {}

  SourceDocumentEntity(
      String id,
      String caseId,
      String originalFilename,
      String objectKey,
      String checksumSha256,
      String verifiedMediaType,
      long sizeBytes,
      Instant retentionDeadline,
      String encryptionKeyReference,
      Instant now) {
    this.id = id;
    this.caseId = caseId;
    this.originalFilename = originalFilename;
    this.objectKey = objectKey;
    this.checksumSha256 = checksumSha256;
    this.verifiedMediaType = verifiedMediaType;
    this.sizeBytes = sizeBytes;
    this.lifecycle = "ACTIVE";
    this.retentionDeadline = retentionDeadline;
    this.encryptionAlgorithm = "AES-256-GCM";
    this.encryptionKeyReference = encryptionKeyReference;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
