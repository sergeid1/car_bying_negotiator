package com.dealanalyzer.listing.persistence;

import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "listing_snapshots")
class ListingSnapshotEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(name = "dealer_id", nullable = false, length = 36)
  String dealerId;

  @Column(name = "provider_listing_id", nullable = false, length = 120)
  String providerListingId;

  @Column(name = "listing_url", nullable = false, length = 1000)
  String listingUrl;

  @Column(length = 17)
  String vin;

  @Column(name = "model_year")
  Integer year;

  @Column(length = 100)
  String make;

  @Column(length = 100)
  String model;

  @Column(name = "trim_name", length = 100)
  String trim;

  Long mileage;

  @Column(name = "advertised_price_minor")
  Long advertisedPriceMinor;

  @Column(length = 3)
  String currency;

  @Column(nullable = false, length = 80)
  String source;

  @Column(name = "retrieved_at", nullable = false)
  Instant retrievedAt;

  @Column(name = "provider_version", nullable = false, length = 100)
  String providerVersion;

  @Column(name = "license_metadata", nullable = false, length = 500)
  String licenseMetadata;

  @Column(name = "artifact_reference", length = 500)
  String artifactReference;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected ListingSnapshotEntity() {}

  ListingSnapshotEntity(
      String id, String caseId, String dealerId, NormalizedListing listing, Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.dealerId = dealerId;
    this.providerListingId = listing.providerListingId();
    this.listingUrl = listing.listingUrl();
    this.vin = listing.vin();
    this.year = listing.year();
    this.make = listing.make();
    this.model = listing.model();
    this.trim = listing.trim();
    this.mileage = listing.mileage();
    this.advertisedPriceMinor = listing.advertisedPriceMinor();
    this.currency = listing.currency();
    this.source = listing.source();
    this.retrievedAt = listing.retrievedAt();
    this.providerVersion = listing.providerVersion();
    this.licenseMetadata = listing.licenseMetadata();
    this.artifactReference = listing.artifactReference();
    this.createdAt = createdAt;
  }
}
