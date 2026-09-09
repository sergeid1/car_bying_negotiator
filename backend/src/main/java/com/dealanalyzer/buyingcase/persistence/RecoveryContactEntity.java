package com.dealanalyzer.buyingcase.persistence;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name = "case_recovery_contacts")
class RecoveryContactEntity {
  @Id @Column(name = "case_id") String caseId;
  @Column(name = "email") String email;
  @Column(name = "expires_at") Instant expiresAt;
  protected RecoveryContactEntity() {}
}
