package com.dealanalyzer.account;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface BuyerAccountRepository extends JpaRepository<BuyerAccountEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<BuyerAccountEntity> findLockedByEmailLookup(String emailLookup);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<BuyerAccountEntity> findLockedById(String id);
  @Query("select a.id from BuyerAccountEntity a where a.verifiedAt is null and a.createdAt <= :cutoff"
      + " and not exists (select l from SignInLinkEntity l where l.accountId = a.id and l.expiresAt > :now)")
  List<String> findAbandonedSignUps(Instant cutoff, Instant now, org.springframework.data.domain.Pageable page);
}

interface SignInLinkRepository extends JpaRepository<SignInLinkEntity, String> {
  interface Owner { String getAccountId(); }
  Optional<Owner> findProjectedByTokenHash(String tokenHash);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<SignInLinkEntity> findLockedByTokenHash(String tokenHash);
  Optional<SignInLinkEntity> findByAccountIdAndRequestKeyHash(String accountId, String requestKeyHash);
  List<SignInLinkEntity> findByAccountIdAndConsumedAtIsNull(String accountId);
  long countByAccountIdAndConsumedAtIsNullAndExpiresAtAfter(String accountId, Instant now);
}

interface AccountSessionRepository extends JpaRepository<AccountSessionEntity, String> {
  List<AccountSessionEntity> findByAccountIdOrderByCreatedAt(String accountId);
}

interface AccountCaseRepository extends JpaRepository<AccountCaseEntity, String> {
  List<AccountCaseEntity> findByAccountIdOrderByAttachedAt(String accountId);
}

interface AccountDeletionJobRepository extends JpaRepository<AccountDeletionJobEntity, String> {
  Optional<AccountDeletionJobEntity> findByAccountId(String accountId);
  Optional<AccountDeletionJobEntity> findByReceiptHash(String receiptHash);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<AccountDeletionJobEntity> findLockedById(String id);
  List<AccountDeletionJobEntity> findByStateOrderByRequestedAt(String state, org.springframework.data.domain.Pageable page);
}
