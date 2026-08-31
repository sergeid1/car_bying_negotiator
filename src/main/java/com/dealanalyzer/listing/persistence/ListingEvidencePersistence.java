package com.dealanalyzer.listing.persistence;

import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import com.dealanalyzer.vehicle.VehicleEvidenceService.DealerView;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ListingEvidencePersistence {
  private final DealerRepository dealers;
  private final ListingSnapshotRepository snapshots;

  ListingEvidencePersistence(
      DealerRepository dealers, ListingSnapshotRepository snapshots) {
    this.dealers = dealers;
    this.snapshots = snapshots;
  }

  @Transactional
  public ListingSnapshotView appendSnapshot(String caseId, NormalizedListing listing) {
    Instant createdAt = Instant.now();
    DealerEntity dealer = dealers.findByCaseIdAndSourceAndProviderDealerId(
            caseId, listing.source(), listing.dealer().providerDealerId())
        .orElseGet(() -> dealers.save(new DealerEntity(
            UUID.randomUUID().toString(), caseId, listing.source(), listing.dealer(), createdAt)));
    ListingSnapshotEntity snapshot = snapshots.save(new ListingSnapshotEntity(
        UUID.randomUUID().toString(), caseId, dealer.id, listing, createdAt));
    return view(snapshot, dealer);
  }

  public Optional<ListingSnapshotView> findLatest(String caseId) {
    return snapshots.findTopByCaseIdOrderByRetrievedAtDesc(caseId)
        .map(snapshot -> view(
            snapshot,
            dealers.findById(snapshot.dealerId)
                .orElseThrow(() -> new IllegalStateException("Listing dealer is missing"))));
  }

  public Optional<ListingSnapshotView> findById(String snapshotId) {
    return snapshots.findById(snapshotId)
        .map(snapshot -> view(
            snapshot,
            dealers.findById(snapshot.dealerId)
                .orElseThrow(() -> new IllegalStateException("Listing dealer is missing"))));
  }

  private ListingSnapshotView view(ListingSnapshotEntity snapshot, DealerEntity dealer) {
    return new ListingSnapshotView(
        snapshot.id,
        snapshot.listingUrl,
        snapshot.vin,
        snapshot.year,
        snapshot.make,
        snapshot.model,
        snapshot.trim,
        snapshot.mileage,
        snapshot.advertisedPriceMinor,
        snapshot.currency,
        snapshot.source,
        snapshot.retrievedAt,
        snapshot.providerVersion,
        snapshot.licenseMetadata,
        new DealerView(dealer.id, dealer.name, dealer.city, dealer.state));
  }
}
