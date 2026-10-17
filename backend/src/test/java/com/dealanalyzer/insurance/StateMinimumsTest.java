package com.dealanalyzer.insurance;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.insurance.InsuranceShoppingService.MinimumLine;
import com.dealanalyzer.insurance.quote.Coverage.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket 138: each pilot state's minimums ship as cited data, and the Buyer's copy is worded from that data alone. */
class StateMinimumsTest {
  static final StateMinimums DATA = new StateMinimums(new ObjectMapper().registerModule(new JavaTimeModule()));

  static MinimumLine line(String state, Type type) {
    return InsuranceShoppingService.minimums(DATA).states().stream().filter(entry -> entry.state().equals(state)).findFirst()
        .orElseThrow().lines().stream().filter(entry -> entry.type() == type).findFirst().orElseThrow();
  }

  @Test void everyPilotStateIsCheckedCitedAndDueForReview() {
    assertThat(DATA.data().states()).extracting(StateMinimums.State::state).containsExactly("FL", "TX", "NC");
    assertThat(DATA.data().states()).allSatisfy(state -> {
      assertThat(state.checkedOn()).isEqualTo(LocalDate.of(2026, 10, 16));
      assertThat(state.reviewOn()).isAfter(state.checkedOn());
      assertThat(state.lines()).allSatisfy(line -> assertThat(line.citation()).contains("https://"));
    });
    assertThat(DATA.state("GA")).isEmpty();
  }

  @Test void floridaRequiresPersonalInjuryProtectionAndPropertyDamageOnly() {
    assertThat(line("FL", Type.PERSONAL_INJURY_PROTECTION)).isEqualTo(new MinimumLine(Type.PERSONAL_INJURY_PROTECTION,
        StateMinimums.Rule.REQUIRED, 1000000L, null, "Florida requires at least $10,000."));
    assertThat(line("FL", Type.PROPERTY_DAMAGE_LIABILITY)).isEqualTo(new MinimumLine(Type.PROPERTY_DAMAGE_LIABILITY,
        StateMinimums.Rule.REQUIRED, null, 1000000L, "Florida requires at least $10,000."));
    assertThat(line("FL", Type.BODILY_INJURY_LIABILITY).rule()).isEqualTo(StateMinimums.Rule.NOT_REQUIRED);
    assertThat(line("FL", Type.BODILY_INJURY_LIABILITY).text()).startsWith("Florida does not require it of most drivers.");
    assertThat(line("FL", Type.COLLISION)).isEqualTo(new MinimumLine(Type.COLLISION, null, null, null, "Florida does not require it."));
  }

  @Test void texasRequiresThirtySixtyTwentyFiveAndIncludesPipAndUninsuredMotoristUnlessRejected() {
    assertThat(line("TX", Type.BODILY_INJURY_LIABILITY)).isEqualTo(new MinimumLine(Type.BODILY_INJURY_LIABILITY,
        StateMinimums.Rule.REQUIRED, 3000000L, 6000000L, "Texas requires at least $30,000 per person and $60,000 per accident."));
    assertThat(line("TX", Type.PROPERTY_DAMAGE_LIABILITY).perAccidentLimitMinor()).isEqualTo(2500000L);
    for (var type : List.of(Type.PERSONAL_INJURY_PROTECTION, Type.UNINSURED_MOTORIST))
      assertThat(line("TX", type)).isEqualTo(new MinimumLine(type, StateMinimums.Rule.INCLUDED_UNLESS_REJECTED, null, null,
          "Texas policies include it unless you reject it in writing. Whether to keep it is your choice."));
  }

  @Test void northCarolinaRequiresFiftyHundredFiftyAndUninsuredMotoristAtTheLiabilityMinimum() {
    assertThat(line("NC", Type.BODILY_INJURY_LIABILITY).perPersonLimitMinor()).isEqualTo(5000000L);
    assertThat(line("NC", Type.BODILY_INJURY_LIABILITY).perAccidentLimitMinor()).isEqualTo(10000000L);
    assertThat(line("NC", Type.PROPERTY_DAMAGE_LIABILITY).perAccidentLimitMinor()).isEqualTo(5000000L);
    assertThat(line("NC", Type.UNINSURED_MOTORIST)).isEqualTo(new MinimumLine(Type.UNINSURED_MOTORIST, StateMinimums.Rule.REQUIRED,
        5000000L, 10000000L, "North Carolina requires at least $50,000 per person and $100,000 per accident. "
            + "Every new or renewed North Carolina policy also includes underinsured motorist coverage."));
    assertThat(line("NC", Type.PERSONAL_INJURY_PROTECTION).text()).isEqualTo("North Carolina does not require it.");
  }

  @Test void dataThatDoesNotFitTheGuideIsRefused() {
    var state = new StateMinimums.State("TX", "Texas", LocalDate.of(2026, 10, 16), LocalDate.of(2027, 1, 1), List.of(
        new StateMinimums.Line(Type.PROPERTY_DAMAGE_LIABILITY, StateMinimums.Rule.REQUIRED, 3000000L, null, null, "https://example.gov")));
    var data = new StateMinimums.Data("v", "d", List.of(state));
    assertThatThrownBy(() -> InsuranceShoppingService.minimums(new StateMinimums(data))).isInstanceOf(IllegalStateException.class);
    var unlimited = new StateMinimums.Data("v", "d", List.of(new StateMinimums.State("TX", "Texas", LocalDate.of(2026, 10, 16),
        LocalDate.of(2027, 1, 1), List.of(new StateMinimums.Line(Type.COLLISION, StateMinimums.Rule.REQUIRED, null, null, null, "c")))));
    assertThatThrownBy(() -> new StateMinimums(unlimited)).isInstanceOf(IllegalStateException.class);
  }
}
