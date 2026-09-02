package com.dealanalyzer.extraction.persistence;

import com.dealanalyzer.extraction.DealTermsValidator.ValidatedLineItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "quote_line_items")
class QuoteLineItemEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "extraction_id", nullable = false, length = 36) String extractionId;
  @Column(name = "original_label", nullable = false, length = 500) String originalLabel;
  @Column(name = "normalized_category", nullable = false, length = 64) String normalizedCategory;
  @Column(name = "amount_minor", nullable = false) long amountMinor;
  @Column(nullable = false, length = 3) String currency;
  @Column(name = "tax_claim", nullable = false, length = 32) String taxClaim;
  @Column(name = "optionality_claim", nullable = false, length = 32) String optionalityClaim;
  @Column(name = "page_number", nullable = false) int pageNumber;
  @Column(name = "region_x", nullable = false, precision = 8, scale = 7) BigDecimal regionX;
  @Column(name = "region_y", nullable = false, precision = 8, scale = 7) BigDecimal regionY;
  @Column(name = "region_width", nullable = false, precision = 8, scale = 7) BigDecimal regionWidth;
  @Column(name = "region_height", nullable = false, precision = 8, scale = 7) BigDecimal regionHeight;
  @Column(nullable = false, precision = 6, scale = 5) BigDecimal confidence;
  @Column(name = "verification_state", nullable = false, length = 32) String verificationState;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected QuoteLineItemEntity() {}

  QuoteLineItemEntity(String id, String extractionId, ValidatedLineItem value, Instant now) {
    this.id = id;
    this.extractionId = extractionId;
    this.originalLabel = value.originalLabel();
    this.normalizedCategory = value.category();
    this.amountMinor = value.amountMinor();
    this.currency = value.currency();
    this.taxClaim = value.taxClaim();
    this.optionalityClaim = value.optionalityClaim();
    this.pageNumber = value.evidence().pageNumber();
    this.regionX = BigDecimal.valueOf(value.evidence().x());
    this.regionY = BigDecimal.valueOf(value.evidence().y());
    this.regionWidth = BigDecimal.valueOf(value.evidence().width());
    this.regionHeight = BigDecimal.valueOf(value.evidence().height());
    this.confidence = BigDecimal.valueOf(value.confidence());
    this.verificationState = value.verificationState();
    this.createdAt = now;
  }
}
