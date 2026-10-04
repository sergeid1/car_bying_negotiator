package com.dealanalyzer.journey;

import com.dealanalyzer.buyingcase.location.PostalJurisdictionResolver;
import com.dealanalyzer.buyingcase.location.PostalResolution;
import com.dealanalyzer.economics.SupportedJurisdictions;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Turns a submitted brief into validated criteria or refuses it before anything is stored. Every
 * field problem is reported at once, each naming its field and the correction. Location is checked
 * only once the rest of the brief is valid, against the same postal data and policy coverage as a
 * Buying Case.
 */
@Component
class ShoppingBriefValidator {
  static final int EARLIEST_YEAR = 1990;
  static final int MAX_MILEAGE = 300_000;
  static final int MIN_PRICE = 1_000;
  static final int MAX_PRICE = 500_000;
  static final int MIN_RADIUS = 5;
  static final int MAX_RADIUS = 500;
  static final int MAX_CHOICES = 20;

  /** Validated criteria in reference-list order, so equal briefs compare equal. */
  record Criteria(int yearFrom, int yearTo, String makeId, String modelId, List<String> trimIds, int maxMileage,
      long priceCeilingMinor, String postalCode, String jurisdiction, int radiusMiles, List<String> mustHaveFeatures,
      List<String> niceToHaveFeatures) {}

  private final VehicleReference reference;
  private final PostalJurisdictionResolver postalCodes;
  private final SupportedJurisdictions supported;
  private final Clock clock;

  @Autowired
  ShoppingBriefValidator(VehicleReference reference, PostalJurisdictionResolver postalCodes,
      SupportedJurisdictions supported) {
    this(reference, postalCodes, supported, Clock.systemUTC());
  }

  ShoppingBriefValidator(VehicleReference reference, PostalJurisdictionResolver postalCodes,
      SupportedJurisdictions supported, Clock clock) {
    this.reference = reference; this.postalCodes = postalCodes; this.supported = supported; this.clock = clock;
  }

  int latestYear() { return Year.now(clock).getValue() + 1; }

  Criteria validate(BriefInput input) {
    if (input == null) throw new JourneyProblem(HttpStatus.BAD_REQUEST, "BRIEF_INVALID",
        "Fill in the Shopping Brief.", List.of());
    var errors = new ArrayList<JourneyProblem.FieldError>();
    int latest = latestYear();
    String yearRange = "a year from " + EARLIEST_YEAR + " to " + latest;
    Integer yearFrom = bounded(errors, "yearFrom", input.yearFrom(), EARLIEST_YEAR, latest,
        "Enter the earliest model year as " + yearRange + ".");
    Integer yearTo = bounded(errors, "yearTo", input.yearTo(), EARLIEST_YEAR, latest,
        "Enter the latest model year as " + yearRange + ".");
    if (yearFrom != null && yearTo != null && yearFrom > yearTo) errors.add(new JourneyProblem.FieldError("yearTo",
        "Enter a latest model year that is the same as or after the earliest model year."));

    var make = reference.make(input.makeId());
    if (make.isEmpty()) errors.add(new JourneyProblem.FieldError("makeId", "Choose a make from the list."));
    var model = make.flatMap(m -> reference.model(m.id(), input.modelId()));
    if (make.isPresent() && model.isEmpty())
      errors.add(new JourneyProblem.FieldError("modelId", "Choose a model of the selected make from the list."));
    List<String> trims = List.of();
    if (model.isPresent()) {
      var chosen = model.get();
      trims = choices(errors, "trimIds", input.trimIds(), id -> reference.trim(chosen, id).isPresent(),
          chosen.trims().stream().map(VehicleReference.Option::id).toList(),
          "Choose trims of the selected model from the list, or choose none to accept any trim.");
    }

    Integer mileage = bounded(errors, "maxMileage", input.maxMileage(), 0, MAX_MILEAGE,
        "Enter a maximum mileage from 0 to 300,000 miles.");
    Integer price = bounded(errors, "priceCeiling", input.priceCeiling(), MIN_PRICE, MAX_PRICE,
        "Enter a price ceiling in whole dollars from $1,000 to $500,000.");
    Integer radius = bounded(errors, "radiusMiles", input.radiusMiles(), MIN_RADIUS, MAX_RADIUS,
        "Enter a search radius from 5 to 500 miles.");
    String postal = input.postalCode() == null ? null : input.postalCode().trim();
    if (postal == null || !PostalJurisdictionResolver.POSTAL_CODE.matcher(postal).matches())
      errors.add(new JourneyProblem.FieldError("postalCode", "Enter a five-digit United States ZIP code."));

    var featureOrder = reference.list().features().stream().map(VehicleReference.Option::id).toList();
    var must = choices(errors, "mustHaveFeatures", input.mustHaveFeatures(), id -> reference.feature(id).isPresent(),
        featureOrder, "Choose must-have features from the list.");
    var nice = choices(errors, "niceToHaveFeatures", input.niceToHaveFeatures(), id -> reference.feature(id).isPresent(),
        featureOrder, "Choose nice-to-have features from the list.");
    if (must.stream().anyMatch(nice::contains)) errors.add(new JourneyProblem.FieldError("niceToHaveFeatures",
        "A feature can be a must-have or a nice-to-have, not both. Remove it from one list."));

    if (!errors.isEmpty()) throw new JourneyProblem(HttpStatus.BAD_REQUEST, "BRIEF_INVALID",
        "Some Shopping Brief fields need a correction. This brief was not saved.", errors);
    String jurisdiction = jurisdiction(postal, input.jurisdiction());
    return new Criteria(yearFrom, yearTo, make.get().id(), model.get().id(), trims, mileage, price * 100L, postal,
        jurisdiction, radius, must, nice);
  }

  private String jurisdiction(String postal, String stated) {
    boolean statedPresent = stated != null && !stated.isBlank();
    PostalResolution resolution = postalCodes.resolve(postal);
    String jurisdiction = switch (resolution.status()) {
      case RESOLVED -> {
        if (statedPresent && !stated.equals(resolution.jurisdiction())) throw invalid("jurisdiction",
            "Remove the state or set it to the state that contains the entered ZIP code.");
        yield resolution.jurisdiction();
      }
      case UNRECOGNIZED -> throw location("postalCode", "UNRECOGNIZED_POSTAL_CODE",
          "That ZIP code is not a recognized United States residential ZIP code. Enter the ZIP code you will "
              + "search from. This brief was not saved.", List.of());
      case CROSSES_STATE_LINE -> {
        if (!statedPresent) throw location("jurisdiction", "POSTAL_CODE_CROSSES_STATE_LINE",
            "That ZIP code crosses a state line, so the state cannot be determined from it. Choose your state. "
                + "This brief was not saved.", resolution.candidates());
        if (!resolution.candidates().contains(stated)) throw invalid("jurisdiction",
            "Choose one of the states that the entered ZIP code crosses into.");
        yield stated;
      }
    };
    if (!supported.supports(jurisdiction)) throw location("postalCode", "JURISDICTION_NOT_SUPPORTED",
        "The service does not support purchases in that state yet, so it cannot shop from this ZIP code. "
            + "This brief was not saved.", supported.all());
    return jurisdiction;
  }

  private static JourneyProblem invalid(String field, String message) {
    return new JourneyProblem(HttpStatus.BAD_REQUEST, "BRIEF_INVALID",
        "Some Shopping Brief fields need a correction. This brief was not saved.",
        List.of(new JourneyProblem.FieldError(field, message)));
  }

  private static JourneyProblem location(String field, String reason, String message, List<String> candidates) {
    return new JourneyProblem(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_NOT_SERVED", message,
        List.of(new JourneyProblem.FieldError(field, message, reason, candidates)));
  }

  private static Integer bounded(List<JourneyProblem.FieldError> errors, String field, Integer value, int min, int max,
      String message) {
    if (value == null || value < min || value > max) { errors.add(new JourneyProblem.FieldError(field, message)); return null; }
    return value;
  }

  /** Known, distinct identifiers, returned in reference-list order. A missing list means none. */
  private static List<String> choices(List<JourneyProblem.FieldError> errors, String field, List<String> values,
      Function<String, Boolean> known, List<String> order, String message) {
    if (values == null) return List.of();
    var seen = new HashSet<String>();
    for (String value : values) {
      if (value == null || !known.apply(value) || !seen.add(value)) { errors.add(new JourneyProblem.FieldError(field, message)); return List.of(); }
    }
    if (values.size() > MAX_CHOICES) {
      errors.add(new JourneyProblem.FieldError(field, "Choose at most " + MAX_CHOICES + " options."));
      return List.of();
    }
    return order.stream().filter(seen::contains).toList();
  }
}
