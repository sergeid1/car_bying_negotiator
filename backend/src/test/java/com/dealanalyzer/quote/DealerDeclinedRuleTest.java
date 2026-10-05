package com.dealanalyzer.quote;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DealerDeclinedRuleTest {
  private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");
  private static DealerDeclinedRule.Offer offer(int version, Long price) {
    return new DealerDeclinedRule.Offer("o" + version, version, price, "USD", START.plusSeconds(version * 10L));
  }
  private static DealerDeclinedRule.Sent sent(int version) {
    return new DealerDeclinedRule.Sent("s" + version, "o" + version, START.plusSeconds(version * 10L + 5));
  }

  @Test void lastConfiguredRoundsNeedCompletedDistinctResponsesThatDoNotLowerPrice() {
    var offers = List.of(offer(1, 2500000L), offer(2, 2500000L), offer(3, 2550000L));
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1), sent(2)), 2)).isTrue();
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1)), 2)).isFalse();
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1)), 1)).isTrue();
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1), sent(2)), 3)).isFalse();
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1), sent(2), sent(3)), 2)).isFalse();
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1), sent(1)), 2)).isFalse();
  }

  @Test void anyLaterVerifiedReductionResetsTheRoundsEvenWithoutAnotherCounteroffer() {
    var offers = List.of(offer(1, 2500000L), offer(2, 2500000L), offer(3, 2500000L), offer(4, 2490000L));
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1), sent(2)), 2)).isFalse();
    var resumed = List.of(offer(1, 2500000L), offer(2, 2400000L), offer(3, 2400000L), offer(4, 2400000L));
    assertThat(DealerDeclinedRule.holds(resumed, List.of(sent(1), sent(2), sent(3)), 2)).isTrue();
    assertThat(DealerDeclinedRule.holds(resumed, List.of(sent(1), sent(2)), 2)).isFalse();
  }

  @Test void missingMoneyCurrencyAndUnansweredRoundsCannotEstablishTheCondition() {
    assertThat(DealerDeclinedRule.holds(List.of(offer(1, 2500000L)), List.of(sent(1)), 1)).isFalse();
    assertThat(DealerDeclinedRule.holds(List.of(offer(1, 2500000L), offer(2, null)), List.of(sent(1)), 1)).isFalse();
    assertThat(DealerDeclinedRule.holds(List.of(offer(1, null), offer(2, 2500000L)), List.of(sent(1)), 1)).isFalse();
    var otherCurrency = new DealerDeclinedRule.Offer("o2", 2, 2500000L, "EUR", START.plusSeconds(20));
    assertThat(DealerDeclinedRule.holds(List.of(offer(1, 2500000L), otherCurrency), List.of(sent(1)), 1)).isFalse();
    var missingCurrency = new DealerDeclinedRule.Offer("o2", 2, 2500000L, null, START.plusSeconds(20));
    assertThat(DealerDeclinedRule.holds(List.of(offer(1, 2500000L), missingCurrency), List.of(sent(1)), 1)).isFalse();
  }

  @Test void firstVerifiedResponseAfterTheActualSendDeterminesTheRound() {
    var offers = List.of(offer(1, 2500000L), offer(2, 2490000L), offer(3, 2550000L));
    assertThat(DealerDeclinedRule.holds(offers, List.of(sent(1)), 1)).isFalse();
    var tooLate = new DealerDeclinedRule.Sent("s", "o1", START.plusSeconds(40));
    assertThat(DealerDeclinedRule.holds(offers, List.of(tooLate), 1)).isFalse();
  }
}
