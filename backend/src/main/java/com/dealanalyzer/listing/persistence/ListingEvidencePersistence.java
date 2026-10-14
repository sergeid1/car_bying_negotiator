package com.dealanalyzer.listing.persistence;

import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import com.dealanalyzer.vehicle.VehicleEvidenceService.DealerView;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ListingEvidencePersistence {
  /** The source of a Listing Snapshot made from the Buyer's own capture of the dealer's ad (ticket 115). */
  public static final String BUYER_CAPTURE = "BUYER_SUPPLIED_LISTING_CAPTURE";
  private final DealerRepository dealers;
  private final ListingSnapshotRepository snapshots;

  ListingEvidencePersistence(
      DealerRepository dealers, ListingSnapshotRepository snapshots) {
    this.dealers = dealers;
    this.snapshots = snapshots;
  }

  @Transactional
  public ListingSnapshotView appendSnapshot(String caseId, NormalizedListing listing) {
    return append(caseId, listing, null);
  }

  /** What the Buyer confirmed from their capture of the ad; never changed once written. */
  public record CaptureDetails(LocalDate capturedOn, List<String> priceConditions, String sourceDocumentId,
      String fieldConfirmations) {}

  @Transactional
  public ListingSnapshotView appendCapture(String caseId, NormalizedListing listing, CaptureDetails details) {
    if (!BUYER_CAPTURE.equals(listing.source())) throw new IllegalArgumentException("Not a Buyer capture");
    return append(caseId, listing, details);
  }

  private ListingSnapshotView append(String caseId, NormalizedListing listing, CaptureDetails details) {
    Instant createdAt = Instant.now();
    DealerEntity dealer = dealers.findByCaseIdAndSourceAndProviderDealerId(
            caseId, listing.source(), listing.dealer().providerDealerId())
        .orElseGet(() -> dealers.save(new DealerEntity(
            UUID.randomUUID().toString(), caseId, listing.source(), listing.dealer(), createdAt)));
    ListingSnapshotEntity entity = new ListingSnapshotEntity(
        UUID.randomUUID().toString(), caseId, dealer.id, listing, createdAt);
    if (details != null) {
      entity.capturedOn = details.capturedOn();
      entity.priceConditions = details.priceConditions().isEmpty() ? null : String.join("\n", details.priceConditions());
      entity.sourceDocumentId = details.sourceDocumentId();
      entity.fieldConfirmations = details.fieldConfirmations();
    }
    return view(snapshots.save(entity), dealer);
  }

  /** The latest snapshot from a listing provider; the Buyer's own captures are read through {@link #findLatestCapture}. */
  public Optional<ListingSnapshotView> findLatest(String caseId) {
    return snapshots.findTopByCaseIdAndSourceNotOrderByRetrievedAtDesc(caseId, BUYER_CAPTURE).map(this::view);
  }

  public Optional<ListingSnapshotView> findLatestCapture(String caseId) {
    return snapshots.findTopByCaseIdAndSourceOrderByRetrievedAtDescIdDesc(caseId, BUYER_CAPTURE).map(this::view);
  }

  public Optional<ListingSnapshotView> findById(String snapshotId) {
    return snapshots.findById(snapshotId).map(this::view);
  }

  private ListingSnapshotView view(ListingSnapshotEntity snapshot) {
    return view(snapshot, dealers.findById(snapshot.dealerId)
        .orElseThrow(() -> new IllegalStateException("Listing dealer is missing")));
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
        new DealerView(dealer.id, dealer.name, dealer.city, dealer.state),
        snapshot.capturedOn,
        snapshot.priceConditions == null ? null : Arrays.asList(snapshot.priceConditions.split("\n")),
        snapshot.sourceDocumentId);
  }
}
