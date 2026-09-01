package com.dealanalyzer.document.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "document_pages")
class DocumentPageEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "document_id", nullable = false, length = 36)
  String documentId;

  @Column(name = "page_number", nullable = false)
  int pageNumber;

  @Column(name = "width_pixels")
  Integer widthPixels;

  @Column(name = "height_pixels")
  Integer heightPixels;

  @Column(name = "resolution_dpi")
  Integer resolutionDpi;

  @Column(name = "likely_missing", nullable = false)
  boolean likelyMissing;

  @Column(name = "likely_duplicate", nullable = false)
  boolean likelyDuplicate;

  @Column(name = "likely_rotated", nullable = false)
  boolean likelyRotated;

  @Column(name = "likely_cropped", nullable = false)
  boolean likelyCropped;

  @Column(name = "normalized_object_key", length = 255)
  String normalizedObjectKey;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "normalized_deleted_at")
  Instant normalizedDeletedAt;

  protected DocumentPageEntity() {}

  DocumentPageEntity(
      String id,
      String documentId,
      int pageNumber,
      Integer widthPixels,
      Integer heightPixels,
      Integer resolutionDpi,
      boolean likelyMissing,
      boolean likelyDuplicate,
      boolean likelyRotated,
      boolean likelyCropped,
      String normalizedObjectKey,
      Instant now) {
    this.id = id;
    this.documentId = documentId;
    this.pageNumber = pageNumber;
    this.widthPixels = widthPixels;
    this.heightPixels = heightPixels;
    this.resolutionDpi = resolutionDpi;
    this.likelyMissing = likelyMissing;
    this.likelyDuplicate = likelyDuplicate;
    this.likelyRotated = likelyRotated;
    this.likelyCropped = likelyCropped;
    this.normalizedObjectKey = normalizedObjectKey;
    this.createdAt = now;
  }
}
