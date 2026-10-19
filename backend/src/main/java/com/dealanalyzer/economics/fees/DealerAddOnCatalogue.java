package com.dealanalyzer.economics.fees;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The add-ons and extra dealer charges dealers print under many names, as versioned data (ticket 142). Labels are
 * read with {@link FeeLabels}, like the government fee and documentary fee checks. Adding a name or an entry is a
 * change to the data file, not to code.
 */
@Component
public class DealerAddOnCatalogue {
  static final String RESOURCE = "fees/dealer-add-ons.v1.json";
  public static final Set<String> KINDS =
      Set.of("OPTIONAL_ADD_ON", "PRICE_INCREASE", "POSSIBLE_DUPLICATE", "FINANCE_PRODUCT", "MANUFACTURER_CHARGE");
  /** Kinds whose recognised lines count towards the total of optional items. */
  public static final Set<String> OPTIONAL_KINDS = Set.of("OPTIONAL_ADD_ON", "FINANCE_PRODUCT");
  private static final Set<String> CATEGORIES = Set.of("DEALER_FEE", "ADD_ON", "GOVERNMENT_CHARGE");

  public record Family(String id, String name) {}
  /**
   * terms name the entry; possible terms only suggest it; unless terms cancel it. repeatOnly entries, such as the
   * destination charge, are a possible repeat only when more than one line names them. loanOnly products cover a loan.
   */
  public record Entry(String id, String family, String kind, String category, String name, String what,
      List<String> terms, List<String> possible, List<String> unless, boolean repeatOnly, boolean loanOnly,
      List<String> examples, String question) {
    public Entry {
      possible = possible == null ? List.of() : List.copyOf(possible);
      unless = unless == null ? List.of() : List.copyOf(unless);
    }
  }
  /** A charge another check covers, which a model suggestion may also name: the doc fee and the state fees. */
  public record OtherCharge(String id, String name, String category) {}
  public record Data(String version, String description, Map<String, String> kinds, List<Family> families,
      List<Entry> entries, List<OtherCharge> otherCharges) {}
  /** A name the Buyer can confirm for a line, and the category it gives the line. */
  public record Charge(String id, String name, String category) {}
  /** RECOGNISED: the label names the entries; POSSIBLE: it only suggests them. */
  public record Match(String tier, List<Entry> entries) {}

  private final Data data;

  @Autowired DealerAddOnCatalogue(ObjectMapper json) { this(read(json)); }

  DealerAddOnCatalogue(Data data) {
    check(data);
    this.data = data;
  }

  private static Data read(ObjectMapper json) {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return json.readValue(input, Data.class);
    } catch (IOException failure) { throw new UncheckedIOException("Dealer add-on catalogue unavailable", failure); }
  }

  public String version() { return data.version(); }
  public Data data() { return data; }
  public String wording(String kind) { return data.kinds().get(kind); }
  public String familyName(String family) {
    return data.families().stream().filter(entry -> entry.id().equals(family)).map(Family::name).findFirst().orElse(family);
  }

  public Optional<Entry> entry(String id) {
    return data.entries().stream().filter(entry -> entry.id().equals(id)).findFirst();
  }

  /** A catalogue entry or another check's charge by id, for a suggestion the Buyer may confirm. */
  public Optional<Charge> charge(String id) {
    if (id == null) return Optional.empty();
    var entry = entry(id);
    if (entry.isPresent()) return Optional.of(new Charge(id, entry.get().name(), entry.get().category()));
    return data.otherCharges().stream().filter(other -> other.id().equals(id)).findFirst()
        .map(other -> new Charge(other.id(), other.name(), other.category()));
  }

  /** The entries a label names, else those it suggests, else null. */
  public Match match(String label) {
    var tokens = FeeLabels.tokens(label);
    List<Entry> named = new ArrayList<>(), suggested = new ArrayList<>();
    for (var entry : data.entries()) {
      if (!FeeLabels.match(tokens, entry.unless()).isEmpty()) continue;
      if (!FeeLabels.match(tokens, entry.terms()).isEmpty()) named.add(entry);
      else if (!FeeLabels.match(tokens, entry.possible()).isEmpty()) suggested.add(entry);
    }
    if (!named.isEmpty()) return new Match("RECOGNISED", List.copyOf(named));
    if (!suggested.isEmpty()) return new Match("POSSIBLE", List.copyOf(suggested));
    return null;
  }

  private static void check(Data data) {
    if (blank(data.version()) || data.kinds() == null || !data.kinds().keySet().equals(KINDS)
        || data.kinds().values().stream().anyMatch(DealerAddOnCatalogue::blank)
        || data.families() == null || data.entries() == null || data.entries().isEmpty() || data.otherCharges() == null)
      throw new IllegalStateException("Dealer add-on catalogue has no version, kinds, families or entries");
    Set<String> families = new HashSet<>();
    for (var family : data.families())
      if (blank(family.id()) || blank(family.name()) || !families.add(family.id()))
        throw new IllegalStateException("Dealer add-on catalogue holds an invalid family");
    Set<String> ids = new HashSet<>();
    for (var entry : data.entries())
      if (blank(entry.id()) || !entry.id().matches("[a-z0-9-]{1,64}") || !ids.add(entry.id())
          || !families.contains(entry.family()) || !KINDS.contains(entry.kind()) || !CATEGORIES.contains(entry.category())
          || blank(entry.name()) || blank(entry.what()) || blank(entry.question())
          || entry.terms() == null || entry.terms().isEmpty() || entry.examples() == null || entry.examples().isEmpty()
          || entry.terms().stream().anyMatch(DealerAddOnCatalogue::blank)
          || entry.possible().stream().anyMatch(DealerAddOnCatalogue::blank))
        throw new IllegalStateException("Dealer add-on catalogue holds an invalid entry " + entry.id());
    for (var other : data.otherCharges())
      if (blank(other.id()) || !other.id().matches("[a-z0-9:-]{1,64}") || !ids.add(other.id()) || blank(other.name())
          || !CATEGORIES.contains(other.category()))
        throw new IllegalStateException("Dealer add-on catalogue holds an invalid other charge " + other.id());
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
