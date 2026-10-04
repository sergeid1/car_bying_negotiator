package com.dealanalyzer.mandate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.mandate.MandatePolicy.AutonomyMode;
import com.dealanalyzer.mandate.MandatePolicy.DealPlanBasis;
import com.dealanalyzer.mandate.MandatePolicy.Disclosure;
import com.dealanalyzer.mandate.MandatePolicy.Request;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MandatePolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
  private static final DealPlanBasis PLAN = new DealPlanBasis("plan", 3, "USD", 2_300_000, 2_400_000);
  private final MandatePolicy policy = new MandatePolicy();

  private static Request request(long target, long ceiling, List<String> disclosures, String mode, Instant expiresAt) {
    return new Request(3, target, ceiling, disclosures, mode, expiresAt, true);
  }

  @Test
  void theBuyerMayLowerTargetAndCeilingButNeverRaiseThemAboveTheDealPlan() {
    var terms = policy.decide(PLAN, request(2_200_000, 2_350_000, List.of("first_name", "PAYING_CASH", "PAYING_CASH"), null, null), NOW);
    assertThat(terms.targetMinor()).isEqualTo(2_200_000);
    assertThat(terms.ceilingMinor()).isEqualTo(2_350_000);
    assertThat(terms.planCeilingMinor()).isEqualTo(2_400_000);
    assertThat(terms.disclosures()).containsExactly(Disclosure.PAYING_CASH, Disclosure.FIRST_NAME);
    assertThat(terms.autonomyMode()).isEqualTo(AutonomyMode.SUPERVISED);
    assertThat(terms.expiresAt()).isEqualTo(NOW.plus(MandatePolicy.DEFAULT_LIFETIME));
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_200_000, 2_400_001, List.of(), null, null), NOW))
        .hasMessageContaining("Deal Plan's walk-away ceiling");
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_300_001, 2_400_000, List.of(), null, null), NOW))
        .hasMessageContaining("Deal Plan's target");
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_200_000, 2_100_000, List.of(), null, null), NOW))
        .hasMessageContaining("above the walk-away ceiling");
    assertThatThrownBy(() -> policy.decide(PLAN, request(0, 2_100_000, List.of(), null, null), NOW))
        .hasMessageContaining("positive");
  }

  @Test
  void aBuyerLimitBelowTheMidpointLowersTheProposedTargetToTheCeiling() {
    var plan = new DealPlanBasis("plan", 1, "USD", 2_300_000, 2_000_000);
    assertThat(plan.targetMinor()).isEqualTo(2_000_000);
  }

  @Test
  void onlyListedDisclosuresAndSupervisedModeAreAccepted() {
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_200_000, 2_300_000, List.of("HOME_ADDRESS"), null, null), NOW))
        .hasMessageContaining("Not a permitted disclosure");
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_200_000, 2_300_000, List.of(), "DELEGATED", null), NOW))
        .hasMessageContaining("Delegated mode is not available");
    assertThatThrownBy(() -> policy.decide(PLAN, request(2_200_000, 2_300_000, List.of(), "AUTOPILOT", null), NOW))
        .hasMessageContaining("Unknown autonomy mode");
  }

  @Test
  void approvalMustBeExplicitCurrentAndBoundedInTime() {
    assertThatThrownBy(() -> policy.decide(PLAN, new Request(3, 1L, 1L, List.of(), null, null, false), NOW))
        .hasMessageContaining("approval");
    assertThatThrownBy(() -> policy.decide(PLAN, new Request(2, 1L, 1L, List.of(), null, null, true), NOW))
        .hasMessageContaining("Deal Plan changed");
    assertThatThrownBy(() -> policy.decide(PLAN, request(1, 1, List.of(), null, NOW.plus(Duration.ofMinutes(30))), NOW))
        .hasMessageContaining("at least one hour");
    assertThatThrownBy(() -> policy.decide(PLAN, request(1, 1, List.of(), null, NOW.plus(Duration.ofDays(31))), NOW))
        .hasMessageContaining("at most 30 days");
    assertThat(policy.decide(PLAN, request(1, 1, List.of(), "supervised", NOW.plus(Duration.ofDays(30))), NOW).expiresAt())
        .isEqualTo(NOW.plus(Duration.ofDays(30)));
  }
}
