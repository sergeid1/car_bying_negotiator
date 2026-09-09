package com.dealanalyzer.buyingcase.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
interface RecoveryTokenRepository extends JpaRepository<RecoveryTokenEntity, String> {
  interface Owner { String getCaseId(); }
  java.util.Optional<Owner> findProjectedByTokenHash(String tokenHash);
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  java.util.Optional<RecoveryTokenEntity> findLockedByTokenHash(String tokenHash);
}
