package com.dealanalyzer.economics.fees;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The title, registration, plate and other state fees each pilot state charges, as versioned, cited data
 * (ticket 139). Adding a state or changing an amount is a change to the data file, not to code.
 */
@Component
public class GovernmentFeeSchedule {
  static final String RESOURCE = "fees/government-fees.v1.json";

  public enum Group { TITLE, REGISTRATION, PLATE, TEMPORARY_TAG, ENVIRONMENTAL, PROPERTY_TAX }

  /** A label term matched at a word start; {@code unless} terms in the same label cancel it. */
  public record Label(List<String> terms, List<Group> groups, List<String> unless) {
    public Label {
      unless = unless == null ? List.of() : List.copyOf(unless);
    }
  }
  /**
   * One fee from the least it can be to the most; maxMinor null means no upper bound the schedule can state.
   * onlyWhen FINANCED applies the fee only to a financed purchase.
   */
  public record Fee(String id, Group group, String name, String purpose, long minMinor, Long maxMinor,
      String onlyWhen, String dependsOn, String citation) {}
  public record State(String state, String name, LocalDate effectiveOn, LocalDate checkedOn, LocalDate reviewOn,
      String scope, String note, List<String> dealerChargeLabels, List<String> taxPackLabels, List<Fee> fees) {}
  public record Data(String version, String description, List<Label> labels, List<State> states) {}

  private final Data data;

  @Autowired GovernmentFeeSchedule(ObjectMapper json) { this(read(json)); }

  GovernmentFeeSchedule(Data data) {
    check(data);
    this.data = data;
  }

  private static Data read(ObjectMapper json) {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return json.readValue(input, Data.class);
    } catch (IOException failure) { throw new UncheckedIOException("Government fee schedule unavailable", failure); }
  }

  public Data data() { return data; }
  public String version() { return data.version(); }
  public Optional<State> state(String state) {
    return data.states().stream().filter(entry -> entry.state().equals(state)).findFirst();
  }

  private static void check(Data data) {
    if (blank(data.version()) || data.states() == null || data.states().isEmpty() || data.labels() == null
        || data.labels().isEmpty())
      throw new IllegalStateException("Government fee schedule has no version, labels or states");
    for (var label : data.labels())
      if (label.terms() == null || label.terms().isEmpty() || label.groups() == null || label.groups().isEmpty()
          || label.terms().stream().anyMatch(GovernmentFeeSchedule::blank))
        throw new IllegalStateException("Government fee schedule holds an invalid label");
    Set<String> states = new HashSet<>();
    for (var state : data.states()) {
      if (state.state() == null || !state.state().matches("[A-Z]{2}") || !states.add(state.state()) || blank(state.name())
          || state.effectiveOn() == null || state.checkedOn() == null || state.reviewOn() == null
          || !state.reviewOn().isAfter(state.checkedOn()) || state.effectiveOn().isAfter(state.checkedOn())
          || blank(state.scope()) || state.dealerChargeLabels() == null || state.taxPackLabels() == null
          || state.fees() == null || state.fees().isEmpty())
        throw new IllegalStateException("Government fee schedule holds an invalid state");
      Set<String> ids = new HashSet<>();
      for (var fee : state.fees())
        if (blank(fee.id()) || !ids.add(fee.id()) || fee.group() == null || blank(fee.name()) || blank(fee.purpose())
            || blank(fee.dependsOn()) || fee.citation() == null || !fee.citation().contains("https://")
            || fee.minMinor() < 0 || fee.maxMinor() != null && fee.maxMinor() < fee.minMinor()
            || fee.onlyWhen() != null && !"FINANCED".equals(fee.onlyWhen()))
          throw new IllegalStateException("Government fee schedule holds an invalid fee for " + state.state());
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
