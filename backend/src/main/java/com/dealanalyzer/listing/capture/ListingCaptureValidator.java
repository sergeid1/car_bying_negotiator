package com.dealanalyzer.listing.capture;

import com.dealanalyzer.listing.capture.ListingCaptureProvider.Field;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Reading;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic checks on what a provider read from a listing capture. Output that cites a field, unit or
 * page the schema does not allow fails closed; a value that cannot be what it claims to be is kept as
 * illegible (null, zero confidence), so the Buyer types it.
 */
public final class ListingCaptureValidator {
  public static final String SCHEMA_VERSION = "listing-capture.v1";
  static final int MAX_CONDITIONS = 10;
  static final int MAX_TEXT = 300;
  static final long MAX_PRICE_MINOR = 10_000_000_00L;
  private static final Map<String, String> UNITS = Map.of(
      "LISTING_PRICE", "USD_MINOR", "VEHICLE_VIN", "VIN", "VEHICLE_YEAR", "YEAR", "VEHICLE_MAKE", "TEXT",
      "VEHICLE_MODEL", "TEXT", "VEHICLE_TRIM", "TEXT", "ODOMETER", "MILES", "DEALER_NAME", "TEXT",
      "CAPTURE_DATE", "DATE", "LISTING_PRICE_CONDITIONS", "TEXT");

  private ListingCaptureValidator() {}

  public static Reading validate(Reading reading, int pageCount) {
    if (blank(reading.extractorVersion()) || blank(reading.modelVersion())
        || !SCHEMA_VERSION.equals(reading.schemaVersion()))
      throw new IllegalArgumentException("Unsupported listing capture reading");
    if (reading.fields().size() > 40 || reading.warnings().size() > 20
        || reading.warnings().stream().anyMatch(warning -> blank(warning) || warning.length() > 500))
      throw new IllegalArgumentException("Listing capture reading exceeds schema bounds");
    Set<String> seen = new HashSet<>();
    int conditions = 0;
    List<Field> fields = new ArrayList<>();
    for (Field field : reading.fields()) {
      if (field == null || !UNITS.containsKey(field.field()) || !UNITS.get(field.field()).equals(field.unit()))
        throw new IllegalArgumentException("Unsupported listing field");
      boolean condition = field.field().equals("LISTING_PRICE_CONDITIONS");
      if (condition ? ++conditions > MAX_CONDITIONS : !seen.add(field.field()))
        throw new IllegalArgumentException("Listing field repeated");
      var region = field.evidence();
      if (region == null || region.pageNumber() < 1 || region.pageNumber() > pageCount
          || !unit(region.x()) || !unit(region.y()) || !unit(region.width()) || !unit(region.height())
          || region.x() + region.width() > 1.0001 || region.y() + region.height() > 1.0001)
        throw new IllegalArgumentException("Listing field evidence is outside the capture");
      if (!Double.isFinite(field.confidence()) || field.confidence() < 0 || field.confidence() > 1)
        throw new IllegalArgumentException("Listing field confidence is out of range");
      if (field.originalWording() == null || field.originalWording().length() > 1000)
        throw new IllegalArgumentException("Listing field wording is out of range");
      String value = field.value() == null ? null : normalized(field.unit(), field.value());
      fields.add(value == null && field.value() != null
          ? new Field(field.field(), field.originalWording(), null, field.unit(), region, 0)
          : new Field(field.field(), field.originalWording(), value, field.unit(), region, field.confidence()));
    }
    return new Reading(reading.extractorVersion(), reading.modelVersion(), reading.schemaVersion(), fields,
        reading.warnings());
  }

  /** The value in its unit's normal form, or null when it cannot be that unit. Also checks Buyer input. */
  static String normalized(String unit, String value) {
    String text = value.strip();
    try {
      return switch (unit) {
        case "USD_MINOR" -> {
          long minor = Long.parseLong(text);
          yield minor > 0 && minor <= MAX_PRICE_MINOR ? Long.toString(minor) : null;
        }
        case "VIN" -> Vin.validated(text);
        case "YEAR" -> {
          int year = Integer.parseInt(text);
          yield year >= 1981 && year <= Year.now().getValue() + 1 ? Integer.toString(year) : null;
        }
        case "MILES" -> {
          long miles = Long.parseLong(text);
          yield miles >= 0 && miles <= 2_000_000 ? Long.toString(miles) : null;
        }
        case "DATE" -> {
          LocalDate date = LocalDate.parse(text);
          yield date.isAfter(LocalDate.now().plusDays(1)) || date.isBefore(LocalDate.of(2000, 1, 1)) ? null : date.toString();
        }
        default -> text.isEmpty() || text.length() > MAX_TEXT || text.chars().anyMatch(Character::isISOControl) ? null : text;
      };
    } catch (NumberFormatException | DateTimeParseException | InvalidVehicleEvidenceException unreadable) {
      return null;
    }
  }

  private static boolean unit(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
