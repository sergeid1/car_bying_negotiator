package com.dealanalyzer.vehicle;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.listing.ListingEvidenceProvider;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.vehicle.persistence.VehicleEvidencePersistence;
import com.dealanalyzer.vehicle.persistence.VehicleIdentityConflictPersistence;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleEvidenceService {
  private final com.dealanalyzer.operations.ProviderMeter meter;
  private final EphemeralBuyingCaseService cases;
  private final VehicleEvidencePersistence persistence;
  private final ListingEvidenceProvider listingProvider;
  private final ListingEvidencePersistence listingPersistence;
  private final VehicleIdentityConflictPersistence conflictPersistence;

  public VehicleEvidenceService(
      EphemeralBuyingCaseService cases,
      VehicleEvidencePersistence persistence,
      ListingEvidenceProvider listingProvider,
      ListingEvidencePersistence listingPersistence,
      VehicleIdentityConflictPersistence conflictPersistence, com.dealanalyzer.operations.ProviderMeter meter) {
    this.meter = meter;
    this.cases = cases;
    this.persistence = persistence;
    this.listingProvider = listingProvider;
    this.listingPersistence = listingPersistence;
    this.conflictPersistence = conflictPersistence;
  }

  @Transactional
  public VehicleEvidenceView establish(
      String caseId, String accessToken, EstablishVehicleRequest request) {
    cases.reopen(caseId, accessToken);
    if (!request.confirmed()) {
      throw new InvalidVehicleEvidenceException(
          "Confirm the selected vehicle before continuing.");
    }
    Instant now = Instant.now();
    VehicleView vehicle;
    if (request.vin() != null && !request.vin().isBlank()) {
      vehicle = persistence.confirmVin(caseId, Vin.validated(request.vin()), now);
    } else {
      var facts = validated(request.manualFacts());
      vehicle = persistence.confirmManual(caseId, facts, now);
    }
    ListingSnapshotView snapshot = null;
    ListingRetrievalView retrieval = null;
    List<IdentityConflictView> conflicts = List.of();
    if (request.listingUrl() != null && !request.listingUrl().isBlank()) {
      ListingEvidenceProvider.ListingRetrieval providerResult;
      try {
        providerResult = meter.call(caseId, com.dealanalyzer.operations.ProviderMeter.Operation.LISTING_RETRIEVAL,
          listingProvider, 1, () -> listingProvider.retrieve(request.listingUrl()), value ->
              !com.dealanalyzer.listing.ListingProviderContract.valid(value, now) ? com.dealanalyzer.operations.ProviderMeter.Status.CONTRACT_FAILURE
              : value.status() == ListingEvidenceProvider.RetrievalStatus.UNSUPPORTED ? com.dealanalyzer.operations.ProviderMeter.Status.BUYER_INPUT
              : value.status() == ListingEvidenceProvider.RetrievalStatus.UNAVAILABLE ? com.dealanalyzer.operations.ProviderMeter.Status.PROVIDER_UNAVAILABLE
              : com.dealanalyzer.operations.ProviderMeter.Status.SUCCESS);
        if (!com.dealanalyzer.listing.ListingProviderContract.valid(providerResult, now))
          providerResult = ListingEvidenceProvider.ListingRetrieval.unavailable("Listing provider returned invalid evidence; no listing values were used.");
      } catch (com.dealanalyzer.operations.ProviderMeter.Failure failure) {
        providerResult = ListingEvidenceProvider.ListingRetrieval.unavailable("Listing provider is unavailable; no listing values were used.");
      }
      if (providerResult.status() == ListingEvidenceProvider.RetrievalStatus.SUPPORTED) {
        snapshot = listingPersistence.appendSnapshot(caseId, providerResult.listing());
        retrieval = new ListingRetrievalView("SNAPSHOT_CREATED", null, null);
        if (vehicle.vin() != null
            && snapshot.vin() != null
            && !vehicle.vin().equals(snapshot.vin())) {
          conflicts = List.of(conflictPersistence.appendVinMismatch(
              caseId, snapshot.id(), vehicle.vin(), snapshot.vin(), now));
        }
      } else {
        String fallback = request.vin() == null || request.vin().isBlank() ? "MANUAL_FACTS" : "VIN";
        retrieval = new ListingRetrievalView(
            providerResult.status() == ListingEvidenceProvider.RetrievalStatus.UNSUPPORTED
                ? "UNSUPPORTED_FALLBACK" : "UNAVAILABLE_FALLBACK", fallback, providerResult.message());
      }
    }
    boolean reliable = conflicts.stream()
        .noneMatch(conflict -> conflict.status().equals("UNRESOLVED"));
    return new VehicleEvidenceView(
        vehicle,
        snapshot,
        retrieval,
        conflicts,
        reliable ? "VEHICLE_CONFIRMED" : "IDENTITY_CONFLICT",
        reliable);
  }

  @Transactional
  public VehicleEvidenceView resolve(
      String caseId,
      String accessToken,
      String conflictId,
      ResolveIdentityConflictRequest request) {
    cases.reopen(caseId, accessToken);
    Instant now = Instant.now();
    if (request.resolution() == null) {
      throw new InvalidVehicleEvidenceException("Choose how to resolve the VIN conflict.");
    }
    if (request.resolution() == ResolveIdentityConflictRequest.Resolution.KEEP_CONFIRMED_VEHICLE) {
      conflictPersistence.keepConfirmed(caseId, conflictId, now);
    } else {
      IdentityConflictView conflict = conflictPersistence.useListing(caseId, conflictId, now);
      ListingSnapshotView listing = listingPersistence.findById(conflict.listingSnapshotId())
          .orElseThrow(() -> new InvalidVehicleEvidenceException("Listing snapshot was not found."));
      // The Buyer's capture of an ad may show only some facts; the car keeps the ones it does not show.
      var existing = persistence.findByCaseId(caseId).orElse(null);
      if (existing != null && ListingEvidencePersistence.BUYER_CAPTURE.equals(listing.source()))
        listing = new ListingSnapshotView(listing.id(), listing.listingUrl(), listing.vin(),
            listing.year() == null ? existing.year() : listing.year(),
            listing.make() == null ? existing.make() : listing.make(),
            listing.model() == null ? existing.model() : listing.model(),
            listing.trim() == null ? existing.trim() : listing.trim(),
            listing.mileage() == null ? existing.mileage() : listing.mileage(),
            listing.advertisedPriceMinor(), listing.currency(), listing.source(), listing.retrievedAt(),
            listing.providerVersion(), listing.licenseMetadata(), listing.dealer(), listing.capturedOn(),
            listing.priceConditions(), listing.sourceDocumentId());
      persistence.confirmListing(caseId, listing, now);
    }
    return current(caseId);
  }

  public VehicleEvidenceView get(String caseId, String accessToken) {
    cases.reopen(caseId, accessToken);
    return current(caseId);
  }

  private VehicleEvidenceView current(String caseId) {
    VehicleView vehicle = persistence.findByCaseId(caseId)
        .orElseThrow(() -> new InvalidVehicleEvidenceException("Vehicle evidence was not found."));
    ListingSnapshotView snapshot = listingPersistence.findLatest(caseId).orElse(null);
    var conflicts = conflictPersistence.findByCaseId(caseId);
    boolean incompleteListing = "LISTING_EVIDENCE".equals(vehicle.confirmationSource());
    boolean reliable = !incompleteListing && conflicts.stream()
        .noneMatch(conflict -> conflict.status().equals("UNRESOLVED"));
    return new VehicleEvidenceView(
        vehicle,
        snapshot,
        null,
        conflicts,
        incompleteListing ? "VEHICLE_CONFIRMATION_REQUIRED" : reliable ? "VEHICLE_CONFIRMED" : "IDENTITY_CONFLICT",
        reliable);
  }

  private EstablishVehicleRequest.ManualVehicleFacts validated(
      EstablishVehicleRequest.ManualVehicleFacts facts) {
    if (facts == null
        || facts.year() == null
        || facts.year() < 1981
        || facts.year() > Year.now().getValue() + 1
        || blank(facts.make())
        || blank(facts.model())
        || facts.mileage() == null
        || facts.mileage() < 0
        || facts.mileage() > 2_000_000) {
      throw new InvalidVehicleEvidenceException(
          "Manual vehicle facts require a valid year, make, model, and non-negative mileage.");
    }
    return facts;
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record VehicleEvidenceView(
      VehicleView vehicle,
      ListingSnapshotView listingSnapshot,
      ListingRetrievalView listingRetrieval,
      List<IdentityConflictView> identityConflicts,
      String status,
      boolean reliableForAnalysis) {}

  public record VehicleView(
      String vin,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      String confirmationSource,
      Instant confirmedAt) {}

  public record ListingSnapshotView(
      String id,
      String listingUrl,
      String vin,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      Long advertisedPriceMinor,
      String currency,
      String source,
      Instant retrievedAt,
      String providerVersion,
      String licenseMetadata,
      DealerView dealer,
      // The rest are set only on the Buyer's own capture of the ad (ticket 115).
      java.time.LocalDate capturedOn,
      List<String> priceConditions,
      String sourceDocumentId) {}

  public record DealerView(String id, String name, String city, String state) {}

  public record ListingRetrievalView(String status, String fallback, String message) {}

  public record IdentityConflictView(
      String id,
      String listingSnapshotId,
      String field,
      String confirmedValue,
      String listingValue,
      String status,
      Instant createdAt,
      Instant resolvedAt) {}
}
