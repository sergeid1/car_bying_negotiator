package com.dealanalyzer.shopping.matching;

import com.dealanalyzer.journey.ReferenceVehicle;
import com.dealanalyzer.journey.ShoppingBrief;
import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.shopping.inventory.InventoryListing;
import com.dealanalyzer.shopping.matching.CandidateVehicle.Kind;
import com.dealanalyzer.shopping.matching.CandidateVehicle.Reason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns inventory listings into Candidate Vehicles. Deterministic and versioned: the same brief, relevant
 * reference details and listings always give the same candidates, reasons and order, whatever order the
 * listings arrive in. There is no score; the order is the fixed sequence of comparisons in {@link #ORDERING}.
 *
 * <p>A must-have is missed only when the listing states a fact that contradicts it; such a listing is left
 * out. A must-have the listing does not state stays unconfirmed and moves the candidate down. Nice-to-have
 * features and relevant reference details only affect order. Listings are read through {@link ListingEvidence}.
 */
public final class CandidateMatcher {
  public static final String VERSION = "candidate-matching.v1";
  public static final String ORDERING = "Listings that state a fact missing a must-have are left out. The rest are "
      + "ordered by: fewest must-haves the listing does not state, then most nice-to-have features listed, then most "
      + "reference details matched, then current listings before older ones, then lower mileage, shorter distance, "
      + "newer model year and lower advertised price. Only your brief, your Reference Vehicles and what each listing "
      + "states are used.";

  private CandidateMatcher() {}

  public record Results(List<CandidateVehicle> candidates, List<CandidateVehicle.Excluded> excluded) {
    public Results { candidates = List.copyOf(candidates); excluded = List.copyOf(excluded); }
  }

  private enum Outcome { MATCHED, MISSED, UNSTATED }
  private record Check(Kind kind, Outcome outcome, String text) {
    Reason reason() { return new Reason(kind, text); }
  }
  private record Evaluated(ListingEvidence listing, List<Check> checks) {
    long count(Kind kind, Outcome outcome) {
      return checks.stream().filter(c -> c.kind == kind && c.outcome == outcome).count();
    }
    List<Reason> reasons(Outcome outcome) {
      return checks.stream().filter(c -> c.outcome == outcome).map(Check::reason).toList();
    }
  }

  public static Results match(ShoppingBrief brief, List<ReferenceVehicle> references, List<InventoryListing> listings) {
    var wanted = referenceValues(references);
    var evaluated = listings.stream().map(ListingEvidence::of)
        .map(listing -> new Evaluated(listing, checks(brief, wanted, listing))).toList();
    var candidates = evaluated.stream().filter(e -> e.count(Kind.MUST_HAVE, Outcome.MISSED) == 0)
        .sorted(ORDER).map(CandidateMatcher::candidate).toList();
    var excluded = evaluated.stream().filter(e -> e.count(Kind.MUST_HAVE, Outcome.MISSED) > 0)
        .sorted(Comparator.comparing(Evaluated::listing, EVIDENCE))
        .map(e -> new CandidateVehicle.Excluded(e.listing.listingId(), e.listing.vin(), title(e.listing.facts()),
            e.checks.stream().filter(c -> c.kind == Kind.MUST_HAVE && c.outcome == Outcome.MISSED).map(Check::reason).toList(),
            snapshot(e.listing)))
        .toList();
    return new Results(candidates, excluded);
  }

  private static final Comparator<ListingEvidence> EVIDENCE = Comparator
      .comparing((ListingEvidence l) -> switch (l.staleness()) { case CURRENT -> 0; case UNKNOWN -> 1; case STALE -> 2; })
      .thenComparing(l -> l.facts().mileage(), Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(l -> l.facts().distanceMiles(), Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(l -> l.facts().year(), Comparator.nullsLast(Comparator.reverseOrder()))
      .thenComparing(ListingEvidence::advertisedPriceMinor, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(ListingEvidence::vin, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(ListingEvidence::source)
      .thenComparing(ListingEvidence::listingId);

  private static final Comparator<Evaluated> ORDER = Comparator
      .comparingLong((Evaluated e) -> e.count(Kind.MUST_HAVE, Outcome.UNSTATED))
      .thenComparing(e -> e.count(Kind.NICE_TO_HAVE, Outcome.MATCHED), Comparator.reverseOrder())
      .thenComparing(e -> e.count(Kind.REFERENCE, Outcome.MATCHED), Comparator.reverseOrder())
      .thenComparing(Evaluated::listing, EVIDENCE);

  private static CandidateVehicle candidate(Evaluated e) {
    var l = e.listing; var f = l.facts();
    return new CandidateVehicle(l.listingId(), l.vin(), title(f), f.year(), f.make(), f.model(), f.trim(), f.mileage(),
        f.distanceMiles(), l.advertisedPriceMinor(), l.currency(), e.reasons(Outcome.MATCHED), e.reasons(Outcome.MISSED),
        e.reasons(Outcome.UNSTATED), snapshot(l));
  }

  private static CandidateVehicle.Snapshot snapshot(ListingEvidence l) {
    return new CandidateVehicle.Snapshot(l.source(), l.retrievedAt(), l.observedAt(), l.staleness(), l.synthetic());
  }

  private static List<Check> checks(ShoppingBrief brief, Map<VehicleAttribute, List<String>> wanted, ListingEvidence listing) {
    var f = listing.facts();
    var checks = new ArrayList<Check>();
    String years = brief.yearFrom() == brief.yearTo() ? String.valueOf(brief.yearFrom())
        : brief.yearFrom() + " to " + brief.yearTo();
    checks.add(f.year() == null ? must(Outcome.UNSTATED, "The listing does not state its model year.")
        : f.year() >= brief.yearFrom() && f.year() <= brief.yearTo()
            ? must(Outcome.MATCHED, "Model year " + f.year() + " is within " + years + ".")
            : must(Outcome.MISSED, "Model year " + f.year() + " is outside " + years + "."));
    String wantedVehicle = brief.make().label() + " " + brief.model().label();
    if (f.make() == null || f.model() == null) checks.add(must(Outcome.UNSTATED, "The listing does not state its make and model."));
    else if (same(f.make(), brief.make().label()) && same(f.model(), brief.model().label()))
      checks.add(must(Outcome.MATCHED, "It is a " + wantedVehicle + "."));
    else checks.add(must(Outcome.MISSED, "It is a " + f.make() + " " + f.model() + ", not a " + wantedVehicle + "."));
    if (!brief.trims().isEmpty()) {
      String trims = brief.trims().stream().map(ShoppingBrief.Choice::label).collect(Collectors.joining(", "));
      checks.add(f.trim() == null ? must(Outcome.UNSTATED, "The listing does not state its trim.")
          : brief.trims().stream().anyMatch(t -> same(t.label(), f.trim()))
              ? must(Outcome.MATCHED, "Trim " + f.trim() + " is one of your trims.")
              : must(Outcome.MISSED, "Trim " + f.trim() + " is not one of your trims (" + trims + ")."));
    }
    String maxMiles = miles(brief.maxMileage());
    checks.add(f.mileage() == null ? must(Outcome.UNSTATED, "The listing does not state its mileage.")
        : f.mileage() <= brief.maxMileage()
            ? must(Outcome.MATCHED, miles(f.mileage()) + " miles is within your " + maxMiles + "-mile maximum.")
            : must(Outcome.MISSED, miles(f.mileage()) + " miles is over your " + maxMiles + "-mile maximum."));
    String ceiling = money(brief.priceCeilingMinor(), brief.currency());
    Long price = listing.advertisedPriceMinor();
    checks.add(price == null ? must(Outcome.UNSTATED, "The listing does not state an advertised price.")
        : !brief.currency().equalsIgnoreCase(listing.currency())
            ? must(Outcome.UNSTATED, "The advertised price is in " + listing.currency() + ", so it cannot be compared with your "
                + ceiling + " ceiling.")
            : price <= brief.priceCeilingMinor()
                ? must(Outcome.MATCHED, "Advertised price " + money(price, listing.currency()) + " is within your " + ceiling + " ceiling.")
                : must(Outcome.MISSED, "Advertised price " + money(price, listing.currency()) + " is over your " + ceiling + " ceiling."));
    checks.add(f.distanceMiles() == null ? must(Outcome.UNSTATED, "The listing does not state its distance from you.")
        : f.distanceMiles() <= brief.radiusMiles()
            ? must(Outcome.MATCHED, miles(f.distanceMiles()) + " miles away, within your " + brief.radiusMiles() + "-mile area.")
            : must(Outcome.MISSED, miles(f.distanceMiles()) + " miles away, outside your " + brief.radiusMiles() + "-mile area."));
    for (var feature : brief.mustHaveFeatures())
      checks.add(lists(f, feature) ? must(Outcome.MATCHED, "Lists " + lower(feature.label()) + ".")
          : must(Outcome.UNSTATED, "The listing does not list " + lower(feature.label()) + ", a must-have."));
    for (var feature : brief.niceToHaveFeatures())
      checks.add(lists(f, feature) ? new Check(Kind.NICE_TO_HAVE, Outcome.MATCHED, "Lists " + lower(feature.label()) + ", a nice-to-have.")
          : new Check(Kind.NICE_TO_HAVE, Outcome.MISSED, "Does not list " + lower(feature.label()) + ", a nice-to-have."));
    wanted.forEach((attribute, values) -> checks.add(reference(attribute, values, f)));
    return checks;
  }

  private static Check reference(VehicleAttribute attribute, List<String> values, InventoryListing.Facts f) {
    String name = lower(attribute.label());
    String reference = "your reference " + name + " (" + String.join(" or ", values) + ")";
    String stated = switch (attribute) {
      case MODEL_YEAR -> f.year() == null ? null : String.valueOf(f.year());
      case MAKE -> f.make();
      case MODEL -> f.model();
      case TRIM -> f.trim();
      default -> null;
    };
    if (stated == null) return new Check(Kind.REFERENCE, Outcome.UNSTATED,
        "The listing does not state its " + name + ", so " + reference + " is unconfirmed.");
    return values.stream().anyMatch(value -> same(value, stated))
        ? new Check(Kind.REFERENCE, Outcome.MATCHED, "Matches " + reference + ".")
        : new Check(Kind.REFERENCE, Outcome.MISSED, capitalize(name) + " " + stated + " differs from " + reference + ".");
  }

  /** Relevant reference details by attribute in display order, each value once whatever its case. */
  private static Map<VehicleAttribute, List<String>> referenceValues(List<ReferenceVehicle> references) {
    var grouped = new EnumMap<VehicleAttribute, LinkedHashMap<String, String>>(VehicleAttribute.class);
    references.stream().flatMap(vehicle -> vehicle.relevantAttributes().stream())
        .filter(a -> a.value() != null && !a.value().isBlank())
        .forEach(a -> grouped.computeIfAbsent(a.attribute(), ignored -> new LinkedHashMap<>())
            .putIfAbsent(a.value().strip().toLowerCase(Locale.ROOT), a.value().strip()));
    var values = new EnumMap<VehicleAttribute, List<String>>(VehicleAttribute.class);
    grouped.forEach((attribute, byKey) -> values.put(attribute, List.copyOf(byKey.values())));
    return values;
  }

  private static Check must(Outcome outcome, String text) { return new Check(Kind.MUST_HAVE, outcome, text); }
  private static boolean lists(InventoryListing.Facts f, ShoppingBrief.Choice feature) {
    return f.features().stream().anyMatch(id -> same(id, feature.id()));
  }
  private static boolean same(String a, String b) { return a.strip().equalsIgnoreCase(b.strip()); }
  private static String lower(String label) {
    // Names such as "Apple CarPlay" keep their capitals; "Heated seats" reads as "heated seats".
    return label.chars().skip(1).anyMatch(Character::isUpperCase) ? label : label.toLowerCase(Locale.ROOT);
  }
  private static String capitalize(String text) { return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1); }
  private static String miles(long value) { return String.format(Locale.US, "%,d", value); }
  private static String money(long minor, String currency) {
    var amount = BigDecimal.valueOf(minor, 2);
    String shown = minor % 100 == 0 ? String.format(Locale.US, "%,d", minor / 100) : String.format(Locale.US, "%,.2f", amount);
    return "USD".equalsIgnoreCase(currency) ? "$" + shown : shown + " " + currency;
  }

  static String title(InventoryListing.Facts f) {
    String named = Stream.of(f.year() == null ? null : String.valueOf(f.year()), f.make(), f.model(), f.trim())
        .filter(Objects::nonNull).collect(Collectors.joining(" "));
    return named.isEmpty() ? "Vehicle with no stated details" : named;
  }
}
