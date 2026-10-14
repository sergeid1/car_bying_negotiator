package com.dealanalyzer.listing.capture;

import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code listing-price-check.v1}: the vehicle price on a Dealer Offer or on desk paperwork against the price in
 * the listing the Buyer saved and confirmed. It says what the ad showed, what the paperwork shows, the
 * difference and one question to ask; it draws no conclusion about advertising rules. Price conditions captured
 * from the ad are carried beside the result, because a conditional ad price can explain part of a gap.
 */
@Component
public class ListingPriceCheck {
  public static final String VERSION = "listing-price-check.v1";
  /** Dates are shown as the Buyer's pilot state reads them. */
  private static final ZoneId ZONE = ZoneId.of("America/New_York");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);

  private final ListingEvidencePersistence listings;

  ListingPriceCheck(ListingEvidencePersistence listings) {
    this.listings = listings;
  }

  /**
   * status: HIGHER, SAME, LOWER, NO_PAPERWORK_PRICE, or DIFFERENT_VIN when the listing and the paperwork name
   * different cars, which are then not compared. paperwork: DEALER_OFFER or DESK_PAPERWORK. verified is true
   * only when the paperwork's price rests on Buyer-verified terms; the listing price is always Buyer-confirmed.
   */
  public record Result(String policyVersion, String status, String listingSnapshotId, LocalDate listingDate,
      Long advertisedPriceMinor, Long paperworkPriceMinor, Long differenceMinor, String paperwork, boolean verified,
      String listingVin, String paperworkVin, List<String> priceConditions, String summary, String deskQuestion) {}

  /** The check against the case's latest confirmed listing capture, or null when the case has none. */
  public Result check(String caseId, JsonNode terms, String paperwork, boolean verified) {
    return listings.findLatestCapture(caseId).map(listing -> compare(listing, terms, paperwork, verified)).orElse(null);
  }

  static Result compare(ListingSnapshotView listing, JsonNode terms, String paperwork, boolean verified) {
    LocalDate date = listingDate(listing);
    String on = date.format(DATE);
    long advertised = listing.advertisedPriceMinor();
    Long price = amount(terms, "SELLING_PRICE");
    String vin = value(terms, "VEHICLE_VIN");
    List<String> conditions = listing.priceConditions() == null ? List.of() : listing.priceConditions();
    if (listing.vin() != null && vin != null && !listing.vin().equals(vin))
      return new Result(VERSION, "DIFFERENT_VIN", listing.id(), date, advertised, price, null, paperwork, verified,
          listing.vin(), vin, conditions,
          "The listing you saved is for VIN " + listing.vin() + ", but this paperwork is for VIN " + vin
              + ", so their prices are not compared.",
          "Is this paperwork for the car in the listing, VIN " + listing.vin() + "?");
    if (price == null)
      return new Result(VERSION, "NO_PAPERWORK_PRICE", listing.id(), date, advertised, null, null, paperwork, verified,
          listing.vin(), vin, conditions,
          "This paperwork shows no vehicle price to set beside the " + money(advertised) + " in the listing you saved on " + on + ".",
          "The listing on " + on + " showed " + money(advertised) + ". What is the vehicle price on this paperwork?");
    long difference = Math.subtractExact(price, advertised);
    if (difference > 0)
      return new Result(VERSION, "HIGHER", listing.id(), date, advertised, price, difference, paperwork, verified,
          listing.vin(), vin, conditions,
          "The listing you saved on " + on + " showed " + money(advertised) + ". The vehicle price on this paperwork is "
              + money(price) + ", " + money(difference) + " more.",
          "The listing on " + on + " showed " + money(advertised) + ". Why is the price on this paperwork " + money(price) + "?");
    return new Result(VERSION, difference == 0 ? "SAME" : "LOWER", listing.id(), date, advertised, price, difference,
        paperwork, verified, listing.vin(), vin, conditions,
        difference == 0
            ? "The vehicle price on this paperwork, " + money(price) + ", is the same as the listing you saved on " + on + "."
            : "The vehicle price on this paperwork, " + money(price) + ", is " + money(-difference)
                + " less than the " + money(advertised) + " in the listing you saved on " + on + ".",
        null);
  }

  /** The date the capture shows it was taken, or else the day the Buyer saved it. */
  static LocalDate listingDate(ListingSnapshotView listing) {
    return listing.capturedOn() != null ? listing.capturedOn() : LocalDate.ofInstant(listing.retrievedAt(), ZONE);
  }

  private static String value(JsonNode terms, String field) {
    if (terms == null) return null;
    for (JsonNode term : terms.path("candidates"))
      if (term.path("field").asText().equals(field) && term.path("value").isTextual() && !term.path("value").asText().isBlank())
        return term.path("value").asText();
    return null;
  }

  private static Long amount(JsonNode terms, String field) {
    String value = value(terms, field);
    try { return value == null ? null : Long.valueOf(value); }
    catch (NumberFormatException unreadable) { return null; }
  }

  static String money(long minor) {
    var format = NumberFormat.getCurrencyInstance(Locale.US);
    if (minor % 100 == 0) format.setMaximumFractionDigits(0);
    return format.format(minor / 100.0);
  }
}
