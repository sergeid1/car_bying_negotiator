package com.dealanalyzer.journey;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;

@Entity @Table(name = "buying_journeys")
class BuyingJourneyEntity {
  @Id String id;
  @Column(name = "account_id") String accountId;
  @Column(name = "request_key_hash") String requestKeyHash;
  @Enumerated(EnumType.STRING) JourneyState state;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "updated_at") Instant updatedAt;
  protected BuyingJourneyEntity() {}
  BuyingJourneyEntity(String id) { this.id = id; }
}

/** One immutable Shopping Brief version. Nothing updates a row once inserted. */
@Entity @Table(name = "shopping_brief_versions") @IdClass(ShoppingBriefVersionEntity.Key.class)
class ShoppingBriefVersionEntity {
  @Id @Column(name = "journey_id", updatable = false) String journeyId;
  @Id @Column(updatable = false) int version;
  @Column(name = "account_id", updatable = false) String accountId;
  @Column(name = "reference_list_version", updatable = false) String referenceListVersion;
  @Column(name = "year_from", updatable = false) int yearFrom;
  @Column(name = "year_to", updatable = false) int yearTo;
  @Column(name = "make_id", updatable = false) String makeId;
  @Column(name = "model_id", updatable = false) String modelId;
  @Column(name = "trim_ids", updatable = false) String trimIds;
  @Column(name = "max_mileage", updatable = false) int maxMileage;
  @Column(name = "price_ceiling_minor", updatable = false) long priceCeilingMinor;
  @Column(updatable = false) String currency;
  @Column(name = "postal_code", updatable = false) String postalCode;
  @Column(updatable = false) String jurisdiction;
  @Column(name = "radius_miles", updatable = false) int radiusMiles;
  @Column(name = "must_have_features", updatable = false) String mustHaveFeatures;
  @Column(name = "nice_to_have_features", updatable = false) String niceToHaveFeatures;
  @Column(name = "created_at", updatable = false) Instant createdAt;
  protected ShoppingBriefVersionEntity() {}

  record Key(String journeyId, int version) implements Serializable {
    Key() { this(null, 0); }
  }
}

/** One Reference Vehicle in one brief version. Nothing updates a row once inserted. */
@Entity @Table(name = "reference_vehicles") @IdClass(ReferenceVehicleEntity.Key.class)
class ReferenceVehicleEntity {
  @Id @Column(name = "journey_id", updatable = false) String journeyId;
  @Id @Column(name = "brief_version", updatable = false) int briefVersion;
  @Id @Column(updatable = false) int position;
  @Column(name = "reference_id", updatable = false) String referenceId;
  @Column(name = "account_id", updatable = false) String accountId;
  @Column(updatable = false) String vin;
  @Column(name = "decode_outcome", updatable = false) String decodeOutcome;
  @Column(updatable = false) String source;
  @Column(name = "decoder_version", updatable = false) String decoderVersion;
  @Column(name = "decoded_at", updatable = false) Instant decodedAt;
  @Column(updatable = false) String attributes;
  protected ReferenceVehicleEntity() {}

  record Key(String journeyId, int briefVersion, int position) implements Serializable {
    Key() { this(null, 0, 0); }
  }
}
