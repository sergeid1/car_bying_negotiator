package com.dealanalyzer.economics.fees;

import com.dealanalyzer.economics.fees.DealerAddOnCatalogue.Entry;
import com.dealanalyzer.listing.capture.ListingPriceCheck;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code dealer-add-on-check.v1}: names each add-on and extra dealer charge on a Dealer Offer or on desk paperwork
 * from the {@link DealerAddOnCatalogue} (ticket 142), says what it is and whether it is usually optional, and gives
 * the question to ask. An uncertain match is shown as "possibly" with a question, and a dealer charge the catalogue
 * does not know is listed as an unrecognised charge, so no dealer charge is hidden. The copy states what a line is
 * and asks; it never says whether the dealer may charge it.
 *
 * <p>Only the Buyer-confirmed {@code chargeId} on a line overrides its label. A model's {@code suggestedChargeId}
 * is never read here: it reaches the findings only after the Buyer confirms it in the verification step.
 */
@Component
public class DealerAddOnCheck {
  public static final String VERSION = "dealer-add-on-check.v1";
  private static final Set<String> SCANNED = Set.of("DEALER_FEE", "ADD_ON", "UNKNOWN");

  private final DealerAddOnCatalogue catalogue;
  private final DocFeeCheck docFees;
  private final GovernmentFeeSchedule schedule;
  private final JdbcTemplate jdbc;

  public DealerAddOnCheck(DealerAddOnCatalogue catalogue, DocFeeCheck docFees, GovernmentFeeSchedule schedule,
      JdbcTemplate jdbc) {
    this.catalogue = catalogue; this.docFees = docFees; this.schedule = schedule; this.jdbc = jdbc;
  }

  /**
   * status: RECOGNISED (named by the catalogue or confirmed by the Buyer), POSSIBLE (only suggested; name is the
   * suggestion) or UNRECOGNISED (a dealer charge the catalogue does not know). kind is null for an unrecognised
   * line. amountMinor is null when the amount could not be read in dollars; it is never treated as zero. verified
   * is true only when the line rests on Buyer-verified terms.
   */
  public record Line(String label, Long amountMinor, String status, List<String> chargeIds, String name,
      String family, String kind, String what, String kindWording, String note, String question, boolean optional,
      boolean confirmedByBuyer, boolean verified, JsonNode evidence) {}
  /**
   * optionalTotalMinor sums the recognised lines of an optional kind; possibleTotalMinor and unrecognisedTotalMinor
   * the other two statuses. unreadAmounts counts lines whose amount could not be read and so is in no total.
   * financed is null when the purchase method is not known.
   */
  public record Result(String policyVersion, String catalogueVersion, Boolean financed, List<Line> lines,
      long optionalTotalMinor, long possibleTotalMinor, long unrecognisedTotalMinor, int unreadAmounts,
      String summary, String listingNote) {}

  /** The check for the case's purchase method, or null when the paperwork lists no add-on or extra dealer charge. */
  public Result check(String caseId, JsonNode terms, ListingPriceCheck.Result listing) {
    String method = jdbc.query("select purchase_method from analysis_cases where id = ?", (r, n) -> r.getString(1), caseId)
        .stream().findFirst().orElse(null);
    Boolean financed = method == null ? null : "FINANCE".equals(method);
    return evaluate(terms, financed, listing);
  }

  public Result evaluate(JsonNode terms, Boolean financed, ListingPriceCheck.Result listing) {
    if (terms == null) return null;
    if (financed == null && hasValue(terms, "AMOUNT_FINANCED")) financed = true;
    List<JsonNode> scanned = new ArrayList<>();
    List<DealerAddOnCatalogue.Match> matches = new ArrayList<>();
    for (JsonNode line : terms.path("lineItems")) {
      if (!SCANNED.contains(line.path("category").asText())) continue;
      var match = match(line);
      if (match != null && "POSSIBLE".equals(match.tier()) && !possibleShown(line)) match = null;
      if (match == null && !unrecognised(line)) continue;
      scanned.add(line);
      matches.add(match);
    }
    if (scanned.isEmpty()) return null;
    // A destination charge is one line; a second one may repeat it.
    long destinations = matches.stream().filter(match -> match != null && "RECOGNISED".equals(match.tier())
        && match.entries().stream().anyMatch(Entry::repeatOnly)).count();
    List<Line> lines = new ArrayList<>();
    long optional = 0, possible = 0, unknown = 0;
    int unread = 0;
    for (int index = 0; index < scanned.size(); index++) {
      var line = describe(scanned.get(index), matches.get(index), financed, destinations > 1);
      lines.add(line);
      if (line.amountMinor() == null) { unread++; continue; }
      if (line.amountMinor() <= 0) continue;
      switch (line.status()) {
        case "RECOGNISED" -> { if (line.optional()) optional = Math.addExact(optional, line.amountMinor()); }
        case "POSSIBLE" -> possible = Math.addExact(possible, line.amountMinor());
        default -> unknown = Math.addExact(unknown, line.amountMinor());
      }
    }
    return new Result(VERSION, catalogue.version(), financed, List.copyOf(lines), optional, possible, unknown, unread,
        summary(lines, optional, possible, unknown, unread), listingNote(lines, listing));
  }

  /**
   * The catalogue's name for a line, "possibly" a name, or null; for naming a line in another finding, such as
   * the Desk Check's added charge.
   */
  public String nameFor(JsonNode line) {
    if (line == null || !SCANNED.contains(line.path("category").asText())) return null;
    var match = match(line);
    if (match == null || "POSSIBLE".equals(match.tier()) && !possibleShown(line)) return null;
    String names = names(match.entries(), " and ");
    return "POSSIBLE".equals(match.tier()) ? "possibly " + names(match.entries(), " or ") : names;
  }

  private DealerAddOnCatalogue.Match match(JsonNode line) {
    String confirmed = line.path("chargeId").asText(null);
    if (confirmed != null) {
      var entry = catalogue.entry(confirmed);
      return entry.map(value -> new DealerAddOnCatalogue.Match("CONFIRMED", List.of(value))).orElse(null);
    }
    return catalogue.match(line.path("originalLabel").asText(""));
  }

  /**
   * A label that only suggests an entry is shown as a possible add-on on an add-on or dealer fee line, unless the
   * doc fee or state fee check names the line. An unclassified line already asks the dealer what it is, and a
   * suggestion there could be a rebate or a tax, so it is not given one.
   */
  private boolean possibleShown(JsonNode line) {
    String category = line.path("category").asText();
    if ("ADD_ON".equals(category)) return true;
    return "DEALER_FEE".equals(category) && docFees.recognition(line) == null
        && GovernmentFeeCheck.groups(schedule, line.path("originalLabel").asText("")).isEmpty();
  }

  /**
   * A dealer charge no check names. A doc fee line and a dealer fee line named as a state fee are left to those
   * checks, and so is a line the Buyer confirmed as one of them.
   */
  private boolean unrecognised(JsonNode line) {
    String category = line.path("category").asText();
    if (!"DEALER_FEE".equals(category) && !"ADD_ON".equals(category)) return false;
    if (line.hasNonNull("chargeId")) return catalogue.charge(line.path("chargeId").asText()).isEmpty();
    if (!"DEALER_FEE".equals(category)) return true;
    return docFees.recognition(line) == null
        && GovernmentFeeCheck.groups(schedule, line.path("originalLabel").asText("")).isEmpty();
  }

  private Line describe(JsonNode line, DealerAddOnCatalogue.Match match, Boolean financed, boolean repeated) {
    String label = line.path("originalLabel").asText("").strip();
    if (label.isEmpty()) label = "Dealer charge";
    String quoted = "“" + label + "”";
    Long amount = line.path("amountMinor").isIntegralNumber() && "USD".equals(line.path("currency").asText("USD"))
        ? line.path("amountMinor").longValue() : null;
    boolean verified = line.path("buyerVerified").asBoolean(false);
    JsonNode evidence = line.path("evidence").isMissingNode() || line.path("evidence").isNull() ? null : line.path("evidence");
    if (match == null)
      return new Line(label, amount, "UNRECOGNISED", List.of(), null, null, null, null, null,
          "Unrecognised charge — ask what it is.", "What is the " + quoted + ", and is it optional?", false, false,
          verified, evidence);
    var entries = match.entries();
    var first = entries.getFirst();
    String kind = first.kind();
    if (first.repeatOnly() && !repeated && entries.size() == 1) kind = "MANUFACTURER_CHARGE";
    boolean possible = "POSSIBLE".equals(match.tier());
    List<String> ids = entries.stream().map(Entry::id).toList();
    String what = String.join(" ", entries.stream().map(Entry::what).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    if (possible) {
      String names = names(entries, " or ");
      return new Line(label, amount, "POSSIBLE", ids, names, catalogue.familyName(first.family()), kind, what,
          catalogue.wording(kind), "Possibly " + names + ".",
          "Is the " + quoted + " " + names + "? What does it cover, and is it optional?", false, false, verified, evidence);
    }
    String names = names(entries, " and ");
    String question = entries.size() == 1 ? first.question()
        : "The " + quoted + " looks like " + names + ". What does it include, and will you take any of it off the price?";
    return new Line(label, amount, "RECOGNISED", ids, names, catalogue.familyName(first.family()), kind, what,
        catalogue.wording(kind), note(entries, kind, financed), question, DealerAddOnCatalogue.OPTIONAL_KINDS.contains(kind),
        "CONFIRMED".equals(match.tier()), verified, evidence);
  }

  /** What the purchase method changes for a finance-office product; null for any other kind. */
  private static String note(List<Entry> entries, String kind, Boolean financed) {
    if (!"FINANCE_PRODUCT".equals(kind)) return null;
    boolean loanOnly = entries.stream().anyMatch(Entry::loanOnly);
    if (Boolean.FALSE.equals(financed))
      return loanOnly ? "It covers a loan, and this is recorded as a cash purchase. Ask what it would cover."
          : "This is recorded as a cash purchase, so its price is paid now, with no interest on it.";
    if (Boolean.TRUE.equals(financed))
      return "Financed with the car, it also adds interest for the whole loan term, on top of its price.";
    return "If it is financed with the car, it also adds interest for the whole loan term.";
  }

  private static String summary(List<Line> lines, long optional, long possible, long unknown, int unread) {
    long named = lines.stream().filter(line -> "RECOGNISED".equals(line.status())).count();
    long suggested = lines.stream().filter(line -> "POSSIBLE".equals(line.status())).count();
    long asked = lines.stream().filter(line -> "UNRECOGNISED".equals(line.status())).count();
    List<String> parts = new ArrayList<>();
    if (named > 0) parts.add(count(named, "named add-on or dealer charge", "named add-ons and dealer charges"));
    if (suggested > 0) parts.add(count(suggested, "possible add-on", "possible add-ons"));
    if (asked > 0) parts.add(count(asked, "unrecognised charge", "unrecognised charges"));
    String text = "This paperwork lists " + join(parts) + ".";
    if (optional > 0) text += " Usually optional items total " + money(optional) + ".";
    if (possible > 0) text += " Possible add-ons total " + money(possible) + ".";
    if (unknown > 0) text += " Unrecognised charges total " + money(unknown) + ".";
    if (unread > 0) text += " " + count(unread, "line's amount", "lines' amounts") + " could not be read, so "
        + (unread == 1 ? "it is" : "they are") + " in no total.";
    return text;
  }

  /** The advertised price beside the add-ons (ticket 115); the price comparison itself stays in the listing check. */
  private static String listingNote(List<Line> lines, ListingPriceCheck.Result listing) {
    if (listing == null || listing.advertisedPriceMinor() == null || "DIFFERENT_VIN".equals(listing.status())) return null;
    long addOns = 0;
    for (var line : lines)
      if (!"UNRECOGNISED".equals(line.status()) && line.amountMinor() != null && line.amountMinor() > 0
          && !"MANUFACTURER_CHARGE".equals(line.kind())) addOns = Math.addExact(addOns, line.amountMinor());
    if (addOns == 0) return null;
    return "The listing you saved showed " + money(listing.advertisedPriceMinor()) + " for the car. This paperwork adds "
        + money(addOns) + " in add-ons and extra dealer charges beside the vehicle price. Ask whether the advertised price"
        + " included them.";
  }

  private static String names(List<Entry> entries, String joiner) {
    return String.join(joiner, entries.stream().map(Entry::name).toList());
  }

  private static String join(List<String> parts) {
    if (parts.size() < 2) return parts.getFirst();
    return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
  }

  private static String count(long count, String one, String many) {
    return count + " " + (count == 1 ? one : many);
  }

  private static boolean hasValue(JsonNode terms, String field) {
    for (JsonNode term : terms.path("candidates"))
      if (field.equals(term.path("field").asText()) && term.path("value").isTextual() && !term.path("value").asText().isBlank())
        return true;
    return false;
  }

  public static String money(long minor) {
    var format = NumberFormat.getCurrencyInstance(Locale.US);
    if (minor % 100 == 0) format.setMaximumFractionDigits(0);
    return format.format(minor / 100.0);
  }
}
