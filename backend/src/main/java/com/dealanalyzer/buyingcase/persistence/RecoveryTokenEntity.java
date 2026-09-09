package com.dealanalyzer.buyingcase.persistence;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name = "case_recovery_tokens")
class RecoveryTokenEntity {
  @Id @Column(name = "token_hash") String tokenHash;
  @Column(name = "case_id") String caseId;
  @Column(name = "delivery_id") String deliveryId;
  @Column(name = "expires_at") Instant expiresAt;
  @Column(name = "consumed_at") Instant consumedAt;
  protected RecoveryTokenEntity() {}
}
