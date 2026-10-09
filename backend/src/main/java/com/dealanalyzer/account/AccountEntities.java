package com.dealanalyzer.account;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "buyer_accounts")
class BuyerAccountEntity {
  @Id String id;
  @Column(name = "email_lookup") String emailLookup;
  @Column(name = "contact_ciphertext") String contactCiphertext;
  String lifecycle;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "updated_at") Instant updatedAt;
  @Column(name = "verified_at") Instant verifiedAt;
  @Column(name = "password_hash") String passwordHash;
  protected BuyerAccountEntity() {}
  BuyerAccountEntity(String id) { this.id = id; }
}

@Entity @Table(name = "buyer_account_sign_in_links")
class SignInLinkEntity {
  @Id @Column(name = "token_hash") String tokenHash;
  @Column(name = "account_id") String accountId;
  @Column(name = "request_key_hash") String requestKeyHash;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "expires_at") Instant expiresAt;
  @Column(name = "consumed_at") Instant consumedAt;
  protected SignInLinkEntity() {}
  SignInLinkEntity(String tokenHash) { this.tokenHash = tokenHash; }
}

@Entity @Table(name = "buyer_account_sessions")
class AccountSessionEntity {
  @Id @Column(name = "token_hash") String tokenHash;
  @Column(name = "account_id") String accountId;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "expires_at") Instant expiresAt;
  @Column(name = "revoked_at") Instant revokedAt;
  @Column(name = "sign_in_method") String signInMethod;
  protected AccountSessionEntity() {}
  AccountSessionEntity(String tokenHash) { this.tokenHash = tokenHash; }
}

@Entity @Table(name = "buyer_account_cases")
class AccountCaseEntity {
  @Id @Column(name = "case_id") String caseId;
  @Column(name = "account_id") String accountId;
  @Column(name = "attached_at") Instant attachedAt;
  protected AccountCaseEntity() {}
  AccountCaseEntity(String caseId) { this.caseId = caseId; }
}

@Entity @Table(name = "buyer_account_deletion_jobs")
class AccountDeletionJobEntity {
  @Id String id;
  @Column(name = "account_id") String accountId;
  @Column(name = "receipt_hash") String receiptHash;
  String state;
  @Column(name = "requested_at") Instant requestedAt;
  int attempts;
  @Column(name = "last_attempt_at") Instant lastAttemptAt;
  @Column(name = "account_removed_at") Instant accountRemovedAt;
  @Column(name = "completed_at") Instant completedAt;
  @Column(name = "failure_code") String failureCode;
  String verification;
  @Column(name = "backup_status") String backupStatus;
  @Column(name = "backup_expires_at") Instant backupExpiresAt;
  @Version @Column(name = "lock_version") long lockVersion;
  protected AccountDeletionJobEntity() {}
  AccountDeletionJobEntity(String id) { this.id = id; }
}
