package com.dealanalyzer.market.persistence;

import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "market_provider_snapshots")
class MarketProviderSnapshotEntity {
  @Id @Column(length = 36) String id;
  @Column(name = "case_id", nullable = false, length = 36) String caseId;
  @Column(nullable = false, length = 100) String source;
  @Column(name = "provider_version", nullable = false, length = 100) String providerVersion;
  @Column(name = "schema_version", nullable = false, length = 100) String schemaVersion;
  @Column(name = "license_metadata", nullable = false, length = 500) String licenseMetadata;
  @Column(name = "artifact_reference", length = 500) String artifactReference;
  @Column(nullable = false, length = 32) String availability;
  @Column(name = "unavailable_reason", length = 500) String unavailableReason;
  @Column(name = "retrieved_at", nullable = false) Instant retrievedAt;
  @Column(name = "created_at", nullable = false) Instant createdAt;

  protected MarketProviderSnapshotEntity() {}

  MarketProviderSnapshotEntity(String id, String caseId, ProviderResult result, Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.source = result.snapshot().source();
    this.providerVersion = result.snapshot().providerVersion();
    this.schemaVersion = result.snapshot().schemaVersion();
    this.licenseMetadata = result.snapshot().licenseMetadata();
    this.artifactReference = result.snapshot().artifactReference();
    this.availability = result.availability().name();
    this.unavailableReason = result.unavailableReason();
    this.retrievedAt = result.snapshot().retrievedAt();
    this.createdAt = createdAt;
  }
}
