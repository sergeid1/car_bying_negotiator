package com.dealanalyzer.market.persistence;

import com.dealanalyzer.market.MarketAssessment.ComparableDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "comparable_snapshots")
class ComparableSnapshotEntity {
  @Id @Column(length = 36) String id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "provider_snapshot_id", nullable = false)
  MarketProviderSnapshotEntity providerSnapshot;
  @Column(name = "provider_observation_id", length = 160) String providerObservationId;
  @Column(name = "listing_url", length = 1000) String listingUrl;
  @Column(name = "model_year") Integer year;
  @Column(length = 100) String make;
  @Column(length = 100) String model;
  @Column(name = "trim_name", length = 100) String trim;
  Long mileage;
  @Column(name = "price_minor") Long priceMinor;
  @Column(length = 3) String currency;
  @Column(length = 200) String geography;
  @Column(name = "observed_at") java.time.Instant observedAt;
  @Column(nullable = false, length = 32) String decision;
  @Column(name = "reason_code", length = 64) String reasonCode;
  @Column(length = 500) String reason;
  @Column(name = "match_factors_json", nullable = false, columnDefinition = "text")
  String matchFactorsJson;
  @Column(name = "adjustment_minor") Long adjustmentMinor;
  @Column(name = "adjustment_precision", length = 32) String adjustmentPrecision;
  @Column(name = "adjustment_explanation", length = 1000) String adjustmentExplanation;
  @Column(name = "adjusted_price_minor") Long adjustedPriceMinor;
  @Column(nullable = false) int ordinal;

  protected ComparableSnapshotEntity() {}

  ComparableSnapshotEntity(
      String id,
      MarketProviderSnapshotEntity providerSnapshot,
      ComparableDecision value,
      String matchFactorsJson,
      int ordinal) {
    var observation = value.observation();
    this.id = id;
    this.providerSnapshot = providerSnapshot;
    this.providerObservationId = observation.providerObservationId();
    this.listingUrl = observation.listingUrl();
    this.year = observation.year();
    this.make = observation.make();
    this.model = observation.model();
    this.trim = observation.trim();
    this.mileage = observation.mileage();
    this.priceMinor = observation.priceMinor();
    this.currency = observation.currency();
    this.geography = observation.geography();
    this.observedAt = observation.observedAt();
    this.decision = value.decision().name();
    this.reasonCode = value.reasonCode();
    this.reason = value.reason();
    this.matchFactorsJson = matchFactorsJson;
    if (value.adjustment() != null) {
      this.adjustmentMinor = value.adjustment().amountMinor();
      this.adjustmentPrecision = value.adjustment().precision();
      this.adjustmentExplanation = value.adjustment().explanation();
    }
    this.adjustedPriceMinor = value.adjustedPriceMinor();
    this.ordinal = ordinal;
  }
}
