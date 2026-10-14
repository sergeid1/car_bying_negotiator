package com.dealanalyzer.listing.capture;

import com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import java.util.List;

/**
 * Reads the fields of a dealer's listing from the Buyer's own capture of it: a screenshot, a saved PDF of the
 * page or pasted text. A provider proposes fields with their evidence regions; {@link ListingCaptureValidator}
 * decides what is kept, and nothing is used until the Buyer confirms it.
 */
public interface ListingCaptureProvider {
  /** The listing fields a capture is read for (ticket 115). */
  List<String> FIELDS = List.of("LISTING_PRICE", "VEHICLE_VIN", "VEHICLE_YEAR", "VEHICLE_MAKE", "VEHICLE_MODEL",
      "VEHICLE_TRIM", "ODOMETER", "DEALER_NAME", "CAPTURE_DATE", "LISTING_PRICE_CONDITIONS");

  Reading read(Capture capture);

  /** Whether the provider meters its own calls, as a model-backed provider does through its harness. */
  default boolean selfMetered() {
    return false;
  }

  /** plainText is true for pasted text, whose page images are rendered line by line from the text. */
  record Capture(String caseId, String documentId, boolean plainText, List<NormalizedDocumentPage> pages) {
    public Capture {
      pages = List.copyOf(pages);
    }
  }

  record Reading(String extractorVersion, String modelVersion, String schemaVersion, List<Field> fields,
      List<String> warnings) {
    public Reading {
      fields = fields == null ? List.of() : List.copyOf(fields);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  /**
   * One field as printed. unit is USD_MINOR (integer cents), VIN, YEAR, MILES, DATE (YYYY-MM-DD) or TEXT;
   * value is null when the field is printed but not legible.
   */
  record Field(String field, String originalWording, String value, String unit, EvidenceRegion evidence,
      double confidence) {}
}
