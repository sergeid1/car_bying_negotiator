package com.dealanalyzer.market.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ComparableSnapshotRepository extends JpaRepository<ComparableSnapshotEntity, String> {
  List<ComparableSnapshotEntity> findByProviderSnapshotIdOrderByOrdinal(String providerSnapshotId);
}
