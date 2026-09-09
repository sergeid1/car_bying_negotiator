package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "analysis_cases")
class AnalysisCaseEntity {
  @Column(nullable = false)
  String lifecycle = "ACTIVE";

  @Column(name = "document_retention_days")
  Integer documentRetentionDays;
  @Column(name = "core_review_blocked", nullable = false)
  boolean coreReviewBlocked;
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "buyer_zip", length = 16)
  String buyerZip;

  @Column(name = "dealer_zip", length = 16)
  String dealerZip;

  @Column(name = "buyer_jurisdiction", length = 64)
  String buyerJurisdiction;

  @Column(name = "dealer_location", length = 200)
  String dealerLocation;

  @Column(name = "purchase_method", nullable = false, length = 16)
  String purchaseMethod;

  @Column(nullable = false, length = 32)
  String status;

  @Column(name = "vehicle_json")
  String vehicleJson;

  @Column(name = "dealer_offer_json")
  String dealerOfferJson;

  @Column(name = "input_json")
  String inputJson;

  @Column(name = "input_fingerprint", length = 64)
  String inputFingerprint;

  @Column(name = "max_selling_price_minor")
  Long maxSellingPriceMinor;

  @Column(name = "max_out_the_door_price_minor")
  Long maxOutTheDoorPriceMinor;

  @Column(name = "access_token_hash", length = 64)
  String accessTokenHash;

  @Column(name = "access_expires_at")
  Instant accessExpiresAt;

  @Column(name = "creation_idempotency_key", length = 200)
  String creationIdempotencyKey;

  @Column(name = "current_report_version", nullable = false)
  int currentReportVersion;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version
  @Column(name = "lock_version", nullable = false)
  long lockVersion;

  protected AnalysisCaseEntity() {}

  static AnalysisCaseEntity ephemeral(
      String id,
      String buyerJurisdiction,
      String dealerLocation,
      String purchaseMethod,
      Long maxSellingPriceMinor,
      Long maxOutTheDoorPriceMinor,
      String accessTokenHash,
      Instant accessExpiresAt,
      String creationIdempotencyKey,
      Instant now) {
    AnalysisCaseEntity entity = new AnalysisCaseEntity();
    entity.id = id;
    entity.buyerJurisdiction = buyerJurisdiction;
    entity.dealerLocation = dealerLocation;
    entity.purchaseMethod = purchaseMethod;
    entity.status = "DRAFT";
    entity.maxSellingPriceMinor = maxSellingPriceMinor;
    entity.maxOutTheDoorPriceMinor = maxOutTheDoorPriceMinor;
    entity.accessTokenHash = accessTokenHash;
    entity.accessExpiresAt = accessExpiresAt;
    entity.creationIdempotencyKey = creationIdempotencyKey;
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  static AnalysisCaseEntity synthetic(
      String id,
      String buyerZip,
      String dealerZip,
      String vehicleJson,
      String dealerOfferJson,
      String inputJson,
      String inputFingerprint,
      String accessTokenHash,
      Instant accessExpiresAt,
      Instant now) {
    AnalysisCaseEntity entity = new AnalysisCaseEntity();
    entity.id = id;
    entity.buyerZip = buyerZip;
    entity.dealerZip = dealerZip;
    entity.purchaseMethod = "CASH";
    entity.status = "ANALYZING";
    entity.vehicleJson = vehicleJson;
    entity.dealerOfferJson = dealerOfferJson;
    entity.inputJson = inputJson;
    entity.inputFingerprint = inputFingerprint;
    entity.accessTokenHash = accessTokenHash;
    entity.accessExpiresAt = accessExpiresAt;
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }
}
