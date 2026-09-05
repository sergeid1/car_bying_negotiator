package com.dealanalyzer.market.persistence;

import com.dealanalyzer.market.MarketAssessment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "market_assessments")
class MarketAssessmentEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "provider_snapshot_id", nullable = false)
  MarketProviderSnapshotEntity providerSnapshot;
  @Column(name = "schema_version", nullable = false, length = 100) String schemaVersion;
  @Column(name = "assessment_version", nullable = false) int version;
  @Column(nullable = false, length = 32) String status;
  @Column(name = "range_low_minor") Long rangeLowMinor;
  @Column(name = "range_high_minor") Long rangeHighMinor;
  @Column(length = 3) String currency;
  @Column(nullable = false, length = 200) String geography;
  @Column(name = "oldest_observation_at") Instant oldestObservationAt;
  @Column(name = "newest_observation_at") Instant newestObservationAt;
  @Column(name = "sample_size", nullable = false) int sampleSize;
  @Column(name = "method_version", nullable = false, length = 100) String methodVersion;
  @Column(nullable = false, length = 32) String confidence;
  @Column(length = 1000) String limitation;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected MarketAssessmentEntity() {}

  MarketAssessmentEntity(
      String id, String caseId, MarketProviderSnapshotEntity providerSnapshot, int version,
      MarketAssessment value, Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.providerSnapshot = providerSnapshot;
    this.schemaVersion = "market-assessment.v1";
    this.version = version;
    this.status = value.status().name();
    if (value.range() != null) {
      this.rangeLowMinor = value.range().lowMinor();
      this.rangeHighMinor = value.range().highMinor();
      this.currency = value.range().currency();
    }
    this.geography = value.geography();
    if (value.vintage() != null) {
      this.oldestObservationAt = value.vintage().oldestObservationAt();
      this.newestObservationAt = value.vintage().newestObservationAt();
    }
    this.sampleSize = value.sampleSize();
    this.methodVersion = value.methodVersion();
    this.confidence = value.confidence().name();
    this.limitation = value.limitation();
    this.createdAt = createdAt;
  }
}
