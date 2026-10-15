package com.dealanalyzer.listing.capture;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.document.DocumentIntakeService;
import com.dealanalyzer.document.DocumentIntakeService.StartedDocumentUpload;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence;
import com.dealanalyzer.listing.ListingEvidenceProvider.ListingRetrieval;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedDealer;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import com.dealanalyzer.listing.ListingProviderContract;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence.CaptureDetails;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import com.dealanalyzer.vehicle.persistence.VehicleEvidencePersistence;
import com.dealanalyzer.vehicle.persistence.VehicleIdentityConflictPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Buyer's own capture of a dealer's listing (ticket 115). The platform never fetches the page: the Buyer
 * uploads a screenshot or a saved PDF of it, or pastes its text, and confirms or corrects each field read from
 * it (or types them) before the case keeps an immutable Listing Snapshot of what the ad showed.
 */
@Service
public class ListingCaptureService {
  public static final String TYPED_VERSION = "buyer-typed-listing.v1";
  public static final String LICENSE = "The Buyer's own capture of the dealer's listing; kept with this case only and "
      + "not redistributed.";
  private static final Map<String, String> REQUEST_FIELDS = new LinkedHashMap<>();
  static {
    REQUEST_FIELDS.put("LISTING_PRICE", "advertisedPriceMinor");
    REQUEST_FIELDS.put("VEHICLE_VIN", "vin");
    REQUEST_FIELDS.put("VEHICLE_YEAR", "year");
    REQUEST_FIELDS.put("VEHICLE_MAKE", "make");
    REQUEST_FIELDS.put("VEHICLE_MODEL", "model");
    REQUEST_FIELDS.put("VEHICLE_TRIM", "trim");
    REQUEST_FIELDS.put("ODOMETER", "mileage");
    REQUEST_FIELDS.put("DEALER_NAME", "dealerName");
    REQUEST_FIELDS.put("CAPTURE_DATE", "capturedOn");
  }

  private final EphemeralBuyingCaseService cases;
  private final DocumentIntakeService intake;
  private final ListingCaptureReader reader;
  private final ListingEvidencePersistence listings;
  private final VehicleEvidencePersistence vehicles;
  private final VehicleIdentityConflictPersistence conflicts;
  private final DealerOfferPersistence offers;
  private final DocumentExtractionPersistence extractions;
  private final ListingPriceCheck priceCheck;
  private final JdbcTemplate jdbc;

  ListingCaptureService(EphemeralBuyingCaseService cases, DocumentIntakeService intake, ListingCaptureReader reader,
      ListingEvidencePersistence listings, VehicleEvidencePersistence vehicles, VehicleIdentityConflictPersistence conflicts,
      DealerOfferPersistence offers, DocumentExtractionPersistence extractions, ListingPriceCheck priceCheck,
      JdbcTemplate jdbc) {
    this.cases = cases; this.intake = intake; this.reader = reader; this.listings = listings; this.vehicles = vehicles;
    this.conflicts = conflicts; this.offers = offers; this.extractions = extractions; this.priceCheck = priceCheck;
    this.jdbc = jdbc;
  }

  /** stage is the processing stage of the capture: QUEUED, NORMALIZING, READY_FOR_EXTRACTION, EXTRACTING, EXTRACTION_COMPLETE or FAILED. */
  public record Capture(String documentId, String filename, String mediaType, int pageCount, Instant uploadedAt,
      String stage, String problem, Reading reading) {}
  public record Reading(String status, String extractorVersion, List<ListingCaptureProvider.Field> fields,
      List<String> warnings) {}
  /** snapshot is the latest confirmed capture; check sets its price beside the latest Dealer Offer, when there is one. */
  public record View(List<Capture> captures, ListingSnapshotView snapshot, ListingPriceCheck.Result check,
      List<com.dealanalyzer.vehicle.VehicleEvidenceService.IdentityConflictView> identityConflicts) {}
  public record TextInput(String text) {}
  /** What the Buyer confirmed, corrected or typed; documentId names the capture it came from, if any. */
  public record Confirmation(String documentId, Long advertisedPriceMinor, String vin, Integer year, String make,
      String model, String trim, Long mileage, String dealerName, LocalDate capturedOn, List<String> priceConditions,
      Boolean confirmed) {}

  public View upload(String caseId, String token, String key, MultipartFile file) {
    cases.reopen(caseId, token);
    if (file == null || file.isEmpty())
      throw ListingCaptureProblem.invalid("file", "Choose a screenshot or a saved PDF of the listing.");
    StartedDocumentUpload ignored = intake.intakeListingCapture(caseId, token, key(key), file);
    return view(caseId, token);
  }

  public View paste(String caseId, String token, String key, TextInput input) {
    cases.reopen(caseId, token);
    if (input == null || input.text() == null || input.text().isBlank())
      throw ListingCaptureProblem.invalid("text", "Paste the text of the listing first.");
    intake.intakeListingText(caseId, token, key(key), input.text());
    return view(caseId, token);
  }

  @Transactional(readOnly = true)
  public View view(String caseId, String token) {
    cases.reopen(caseId, token);
    List<Capture> captures = jdbc.query("""
        select d.id, d.original_filename, d.verified_media_type, d.page_count, d.created_at,
          (select op.stage from document_processing_operations op where op.document_id = d.id
           order by op.updated_at desc fetch first 1 rows only),
          (select op.error_message from document_processing_operations op where op.document_id = d.id
           order by op.updated_at desc fetch first 1 rows only)
        from source_documents d where d.case_id = ? and d.purpose = 'LISTING_CAPTURE' and d.lifecycle = 'ACTIVE'
        order by d.created_at desc, d.id desc fetch first 5 rows only
        """, (r, n) -> new Capture(r.getString(1), r.getString(2), r.getString(3), r.getInt(4),
            r.getTimestamp(5).toInstant(), r.getString(6), r.getString(7), null), caseId)
        .stream().map(capture -> new Capture(capture.documentId(), capture.filename(), capture.mediaType(),
            capture.pageCount(), capture.uploadedAt(), capture.stage(), capture.problem(),
            reader.latest(caseId, capture.documentId()).map(reading -> new Reading(reading.status(),
                reading.extractorVersion(), reading.fields(), reading.warnings())).orElse(null)))
        .toList();
    var snapshot = listings.findLatestCapture(caseId).orElse(null);
    var offer = offers.latestOffer(caseId).orElse(null);
    var check = snapshot == null || offer == null ? null
        : priceCheck.check(caseId, offer.terms(), "DEALER_OFFER", "VERIFIED".equals(offer.status()));
    var open = snapshot == null ? List.<com.dealanalyzer.vehicle.VehicleEvidenceService.IdentityConflictView>of()
        : conflicts.findByCaseId(caseId).stream()
            .filter(conflict -> conflict.listingSnapshotId().equals(snapshot.id()) && conflict.status().equals("UNRESOLVED"))
            .toList();
    return new View(captures, snapshot, check, open);
  }

  /** A normalized page of one of this case's captures, so the Buyer can check a field against where it was read. */
  public byte[] page(String caseId, String token, String documentId, int pageNumber) {
    cases.reopen(caseId, token);
    return intake.listingCapturePage(caseId, documentId, pageNumber);
  }

  @Transactional
  public View confirm(String caseId, String token, Confirmation input) {
    cases.reopen(caseId, token);
    if (input == null || !Boolean.TRUE.equals(input.confirmed()))
      throw ListingCaptureProblem.invalid("confirmed", "Check each field against the listing, then confirm it.");
    String documentId = blank(input.documentId()) ? null : input.documentId();
    if (documentId != null && jdbc.queryForObject("""
        select count(*) from source_documents where id = ? and case_id = ? and purpose = 'LISTING_CAPTURE' and lifecycle = 'ACTIVE'
        """, Integer.class, documentId, caseId) == 0)
      throw new ResourceNotFoundException("Listing capture not found");
    var values = values(input);
    var reading = documentId == null ? null : reader.latest(caseId, documentId).orElse(null);
    List<String> outcomes = new ArrayList<>();
    for (var field : REQUEST_FIELDS.keySet()) {
      String value = values.get(field);
      if (value == null) continue;
      String read = reading == null ? null : reading.fields().stream().filter(item -> item.field().equals(field))
          .map(ListingCaptureProvider.Field::value).filter(java.util.Objects::nonNull).findFirst().orElse(null);
      outcomes.add(field + "=" + (read == null ? "TYPED" : read.equalsIgnoreCase(value) ? "AS_READ" : "CORRECTED"));
    }
    Instant now = Instant.now();
    String snapshotKey = documentId == null ? UUID.randomUUID().toString() : documentId;
    String dealerName = values.get("DEALER_NAME") != null ? values.get("DEALER_NAME") : intakeDealer(caseId);
    var listing = new NormalizedListing(
        "capture:" + snapshotKey, null, values.get("VEHICLE_VIN"),
        values.get("VEHICLE_YEAR") == null ? null : Integer.valueOf(values.get("VEHICLE_YEAR")),
        values.get("VEHICLE_MAKE"), values.get("VEHICLE_MODEL"), values.get("VEHICLE_TRIM"),
        values.get("ODOMETER") == null ? null : Long.valueOf(values.get("ODOMETER")),
        Long.valueOf(values.get("LISTING_PRICE")), "USD",
        new NormalizedDealer("capture:" + dealerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"),
            dealerName, null, null),
        ListingEvidencePersistence.BUYER_CAPTURE, now,
        reading != null && reading.status().equals("READ") ? reading.extractorVersion() : TYPED_VERSION,
        LICENSE, documentId == null ? "buyer-typed:" + snapshotKey : "source-document:" + documentId);
    if (!ListingProviderContract.valid(ListingRetrieval.supported(listing), now))
      throw new IllegalStateException("A confirmed listing capture must satisfy the listing provider contract");
    var snapshot = listings.appendCapture(caseId, listing, new CaptureDetails(
        input.capturedOn(), conditions(input.priceConditions()), documentId, String.join(";", outcomes)));
    // A different VIN from the case's car, or from the dealer's paperwork when the car has no VIN yet, is the
    // existing blocking conflict, which the Buyer resolves against the case's car. Without a car, verification
    // still refuses paperwork whose VIN differs from the ad's.
    var vehicle = vehicles.findByCaseId(caseId).orElse(null);
    if (snapshot.vin() != null && vehicle != null) {
      String other = vehicle.vin() != null ? vehicle.vin() : paperworkVin(caseId);
      if (other != null && !other.equals(snapshot.vin()))
        conflicts.appendVinMismatch(caseId, snapshot.id(), other, snapshot.vin(), now);
    }
    return view(caseId, token);
  }

  /** The VIN on the latest Dealer Offer, or else on the latest dealer paperwork read for this case. */
  private String paperworkVin(String caseId) {
    var offer = offers.latestOffer(caseId).orElse(null);
    if (offer != null) {
      for (JsonNode term : offer.terms().path("candidates"))
        if ("VEHICLE_VIN".equals(term.path("field").asText()) && term.path("value").isTextual())
          return term.path("value").asText();
    }
    return extractions.latestForCase(caseId).flatMap(extraction -> extraction.candidates().stream()
        .filter(candidate -> candidate.field().equals("VEHICLE_VIN")).findFirst()
        .map(candidate -> candidate.currentCorrection() == null ? candidate.normalizedValue()
            : candidate.currentCorrection().normalizedValue())).orElse(null);
  }

  private String intakeDealer(String caseId) {
    return jdbc.query("select name from dealers where case_id = ? and source = 'BUYER_INTAKE' fetch first 1 rows only",
        (r, n) -> r.getString(1), caseId).stream().findFirst().orElse("Dealer not named in the listing");
  }

  private static Map<String, String> values(Confirmation input) {
    Map<String, String> values = new LinkedHashMap<>();
    if (input.advertisedPriceMinor() == null)
      throw ListingCaptureProblem.invalid("advertisedPriceMinor", "Enter the price the listing shows for the car.");
    put(values, "LISTING_PRICE", "USD_MINOR", input.advertisedPriceMinor().toString(),
        "Enter the listing price as a dollar amount above zero.");
    if (!blank(input.vin()))
      put(values, "VEHICLE_VIN", "VIN", input.vin().replaceAll("[\\s-]", ""),
          "Check the VIN: it should be 17 letters and numbers and pass the VIN check digit.");
    if (input.year() != null) put(values, "VEHICLE_YEAR", "YEAR", input.year().toString(), "Enter a model year, like 2021.");
    text(values, "VEHICLE_MAKE", input.make());
    text(values, "VEHICLE_MODEL", input.model());
    text(values, "VEHICLE_TRIM", input.trim());
    if (input.mileage() != null)
      put(values, "ODOMETER", "MILES", input.mileage().toString(), "Enter the mileage as a whole number of miles.");
    text(values, "DEALER_NAME", input.dealerName());
    if (input.capturedOn() != null)
      put(values, "CAPTURE_DATE", "DATE", input.capturedOn().toString(), "Enter the date you saved the listing, no later than today.");
    return values;
  }

  private static void put(Map<String, String> values, String field, String unit, String value, String problem) {
    String normalized = ListingCaptureValidator.normalized(unit, value);
    if (normalized == null) throw ListingCaptureProblem.invalid(REQUEST_FIELDS.get(field), problem);
    values.put(field, normalized);
  }

  private static void text(Map<String, String> values, String field, String value) {
    if (blank(value)) return;
    put(values, field, "TEXT", value, "Keep this to " + ListingCaptureValidator.MAX_TEXT + " characters.");
  }

  private static List<String> conditions(List<String> input) {
    if (input == null) return List.of();
    List<String> conditions = input.stream().filter(value -> !blank(value)).map(String::strip).distinct().toList();
    if (conditions.size() > ListingCaptureValidator.MAX_CONDITIONS)
      throw ListingCaptureProblem.invalid("priceConditions", "Keep up to " + ListingCaptureValidator.MAX_CONDITIONS
          + " pieces of fine print.");
    for (String condition : conditions)
      if (ListingCaptureValidator.normalized("TEXT", condition) == null)
        throw ListingCaptureProblem.invalid("priceConditions", "Keep each piece of fine print to "
            + ListingCaptureValidator.MAX_TEXT + " characters.");
    return conditions;
  }

  private static String key(String key) {
    if (blank(key) || key.length() > 200)
      throw new ListingCaptureProblem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
          "Send an Idempotency-Key header with each capture.", null);
    return "listing-capture:" + key;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
