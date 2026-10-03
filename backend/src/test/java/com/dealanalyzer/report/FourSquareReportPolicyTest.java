package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class FourSquareReportPolicyTest {
  @Test void combinedWorksheetFindingPreservesTheFirstTwoExistingPriorityReasons() {
    var existing = List.of("Selling price exceeds the approved walk-away ceiling.",
        "The quoted OTD differs from the recorded cash calculation.", "Market analysis is unavailable.");
    var reasons = new FourSquareReportPolicy().topReasons(
        List.of("The out-the-door total is missing.", "The loan term is missing."), existing);

    assertThat(reasons).hasSize(3);
    assertThat(reasons.getFirst()).contains("out-the-door total and loan term are missing", "do not infer either");
    assertThat(reasons.subList(1, 3)).containsExactlyElementsOf(existing.subList(0, 2));
  }

  @Test void aSingleWorksheetFindingStillKeepsTwoExistingReasons() {
    assertThat(new FourSquareReportPolicy().topReasons(List.of("The total is missing."),
        List.of("Price exceeds the ceiling.", "Market comparison is unavailable.", "Third reason.")))
        .containsExactly("The total is missing.", "Price exceeds the ceiling.", "Market comparison is unavailable.");
  }

  @Test void reportsWithoutWorksheetFindingsKeepTheirExistingReasons() {
    var existing = List.of("Price exceeds the ceiling.", "Quoted total differs.", "Market comparison is unavailable.");
    assertThat(new FourSquareReportPolicy().topReasons(List.of(), existing)).isEqualTo(existing);
  }
}
