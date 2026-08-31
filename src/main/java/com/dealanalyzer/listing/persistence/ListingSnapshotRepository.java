package com.dealanalyzer.listing.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ListingSnapshotRepository extends JpaRepository<ListingSnapshotEntity, String> {
  Optional<ListingSnapshotEntity> findTopByCaseIdOrderByRetrievedAtDesc(String caseId);
}
