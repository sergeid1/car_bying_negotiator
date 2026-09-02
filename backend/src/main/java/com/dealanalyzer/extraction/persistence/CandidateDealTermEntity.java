package com.dealanalyzer.extraction.persistence;

import com.dealanalyzer.extraction.DealTermsValidator.ValidatedCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "candidate_deal_terms")
class CandidateDealTermEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(name = "field_key", nullable = false, length = 64) String fieldKey;
  @Column(name = "original_wording", nullable = false, length = 2000) String originalWording;
  @Column(name = "normalized_value", length = 500) String normalizedValue;
  @Column(name = "normalized_unit", length = 32) String normalizedUnit;
  @Column(name = "page_number", nullable = false) int pageNumber;
  @Column(name = "region_x", nullable = false, precision = 8, scale = 7) BigDecimal regionX;
  @Column(name = "region_y", nullable = false, precision = 8, scale = 7) BigDecimal regionY;
  @Column(name = "region_width", nullable = false, precision = 8, scale = 7) BigDecimal regionWidth;
  @Column(name = "region_height", nullable = false, precision = 8, scale = 7) BigDecimal regionHeight;
  @Column(nullable = false, precision = 6, scale = 5) BigDecimal confidence;
  @Column(nullable = false) boolean material;
  @Column(name = "verification_state", nullable = false, length = 32) String verificationState;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected CandidateDealTermEntity() {}

  CandidateDealTermEntity(String id, String extractionId, ValidatedCandidate value, Instant now) {
    this.id = id;
    this.extractionId = extractionId;
    this.fieldKey = value.field();
    this.originalWording = value.originalWording();
    this.normalizedValue = value.normalizedValue();
    this.normalizedUnit = value.normalizedUnit();
    this.pageNumber = value.evidence().pageNumber();
    this.regionX = BigDecimal.valueOf(value.evidence().x());
    this.regionY = BigDecimal.valueOf(value.evidence().y());
    this.regionWidth = BigDecimal.valueOf(value.evidence().width());
    this.regionHeight = BigDecimal.valueOf(value.evidence().height());
    this.confidence = BigDecimal.valueOf(value.confidence());
    this.material = value.material();
    this.verificationState = value.verificationState();
    this.createdAt = now;
  }
}
