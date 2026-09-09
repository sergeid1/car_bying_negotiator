package com.dealanalyzer.buyingcase.persistence;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name = "case_delivery_requests")
class DeliveryRequestEntity {
  @Id @Column(name = "id") String id;
  @Column(name = "case_id") String caseId;
  @Column(name = "request_key") String requestKey;
  @Column(name = "email") String email;
  @Column(name = "expires_at") Instant expiresAt;
  protected DeliveryRequestEntity() {}
}
