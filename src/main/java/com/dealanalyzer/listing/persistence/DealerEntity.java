package com.dealanalyzer.listing.persistence;

import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedDealer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dealers")
class DealerEntity {
  @Id
  @Column(length = 36)
  String id;

  @Column(name = "case_id", nullable = false, length = 36)
  String caseId;

  @Column(nullable = false, length = 80)
  String source;

  @Column(name = "provider_dealer_id", nullable = false, length = 120)
  String providerDealerId;

  @Column(nullable = false, length = 200)
  String name;

  @Column(length = 100)
  String city;

  @Column(name = "state_code", length = 20)
  String state;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected DealerEntity() {}

  DealerEntity(
      String id, String caseId, String source, NormalizedDealer dealer, Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.source = source;
    this.providerDealerId = dealer.providerDealerId();
    this.name = dealer.name();
    this.city = dealer.city();
    this.state = dealer.state();
    this.createdAt = createdAt;
  }
}
