package com.dealanalyzer.loan.preapproval;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicPreApprovalConformanceTest extends PreApprovalAdapterConformance {
  @Test void fixedClockMakesEveryFixtureRepeatable() {
    for (var fixture : Fixture.values())
      assertThat(adapter(fixture).request(application(), authorization()))
          .isEqualTo(adapter(fixture).request(application(), authorization()));
  }
  @Override protected Instant now() { return Instant.parse("2026-10-15T12:00:00Z"); }
  @Override protected PreApprovalAdapter adapter(Fixture fixture) {
    return new DeterministicFakePreApprovalAdapter(Clock.fixed(now(), ZoneOffset.UTC),
        DeterministicFakePreApprovalAdapter.Scenario.valueOf(fixture.name()));
  }
}
