package com.dealanalyzer.insurance;

import com.dealanalyzer.insurance.quote.Coverage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The coverage each pilot state requires, as versioned, cited data (ticket 138). Adding a state or changing a
 * limit is a change to the data file, not to code: nothing outside this list names a state's minimum.
 */
@Component
public class StateMinimums {
  static final String RESOURCE = "insurance/state-minimums.v1.json";

  /**
   * REQUIRED carries the least limits a policy may have; INCLUDED_UNLESS_REJECTED is in every policy unless the
   * Buyer rejects it in writing; NOT_REQUIRED says so with a note.
   */
  public enum Rule { REQUIRED, INCLUDED_UNLESS_REJECTED, NOT_REQUIRED }

  public record Line(Coverage.Type type, Rule rule, Long perPersonLimitMinor, Long perAccidentLimitMinor, String note,
      String citation) {}
  public record State(String state, String name, LocalDate checkedOn, LocalDate reviewOn, List<Line> lines) {}
  public record Data(String version, String description, List<State> states) {}

  private final Data data;

  @Autowired StateMinimums(ObjectMapper json) { this(read(json)); }

  StateMinimums(Data data) {
    check(data);
    this.data = data;
  }

  private static Data read(ObjectMapper json) {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return json.readValue(input, Data.class);
    } catch (IOException failure) { throw new UncheckedIOException("Insurance state minimums unavailable", failure); }
  }

  public Data data() { return data; }
  public String version() { return data.version(); }
  public Optional<State> state(String state) {
    return data.states().stream().filter(entry -> entry.state().equals(state)).findFirst();
  }

  private static void check(Data data) {
    if (data.version() == null || data.version().isBlank() || data.states() == null || data.states().isEmpty())
      throw new IllegalStateException("Insurance state minimums have no version or states");
    var states = new HashSet<String>();
    for (var state : data.states()) {
      if (state.state() == null || !state.state().matches("[A-Z]{2}") || !states.add(state.state()) || blank(state.name())
          || state.checkedOn() == null || state.reviewOn() == null || !state.reviewOn().isAfter(state.checkedOn())
          || state.lines() == null)
        throw new IllegalStateException("Insurance state minimums hold an invalid state");
      var types = new HashSet<Coverage.Type>();
      for (var line : state.lines()) {
        boolean amounts = line.perPersonLimitMinor() != null || line.perAccidentLimitMinor() != null;
        if (line.type() == null || line.rule() == null || !types.add(line.type()) || blank(line.citation())
            || (line.rule() == Rule.REQUIRED) != amounts || (line.rule() == Rule.NOT_REQUIRED && blank(line.note()))
            || notPositive(line.perPersonLimitMinor()) || notPositive(line.perAccidentLimitMinor()))
          throw new IllegalStateException("Insurance state minimums hold an invalid line for " + state.state());
      }
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static boolean notPositive(Long amount) { return amount != null && amount <= 0; }
}
