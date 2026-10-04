package com.dealanalyzer.journey;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface BuyingJourneyRepository extends JpaRepository<BuyingJourneyEntity, String> {
  List<BuyingJourneyEntity> findByAccountIdOrderByCreatedAtDesc(String accountId);
  Optional<BuyingJourneyEntity> findByIdAndAccountId(String id, String accountId);
  @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<BuyingJourneyEntity> findLockedByIdAndAccountId(String id, String accountId);
  Optional<BuyingJourneyEntity> findByAccountIdAndRequestKeyHash(String accountId, String requestKeyHash);
}

interface ShoppingBriefVersionRepository extends JpaRepository<ShoppingBriefVersionEntity, ShoppingBriefVersionEntity.Key> {
  List<ShoppingBriefVersionEntity> findByJourneyIdOrderByVersion(String journeyId);
  Optional<ShoppingBriefVersionEntity> findFirstByJourneyIdOrderByVersionDesc(String journeyId);
  Optional<ShoppingBriefVersionEntity> findByJourneyIdAndVersion(String journeyId, int version);
}

interface ReferenceVehicleRepository extends JpaRepository<ReferenceVehicleEntity, ReferenceVehicleEntity.Key> {
  List<ReferenceVehicleEntity> findByJourneyIdAndBriefVersionOrderByPosition(String journeyId, int briefVersion);
}
