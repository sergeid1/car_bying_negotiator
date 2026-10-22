package com.dealanalyzer.economics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ticket 141: which cases count as a car registered in one state and bought in another. */
class CrossStatePurchaseTest {
  @Test void aDealerInAnotherStateIsADifferenceAndAnUnknownSideIsNot() {
    assertThat(CrossStatePurchase.stateOf("Dallas, TX", null)).isEqualTo("US-TX");
    assertThat(CrossStatePurchase.stateOf("Myrtle Beach, SC", null)).isEqualTo("US-SC");
    assertThat(CrossStatePurchase.stateOf("Dallas", null)).isNull();
    assertThat(CrossStatePurchase.stateOf(null, null)).isNull();

    assertThat(CrossStatePurchase.differs("US-NC", "US-TX")).isTrue();
    assertThat(CrossStatePurchase.differs("US-NC", "US-SC")).as("a dealer state with no pack").isTrue();
    assertThat(CrossStatePurchase.differs("US-FL:Miami-Dade", "US-FL")).as("a Florida county is Florida").isFalse();
    assertThat(CrossStatePurchase.differs("US-FL:Miami-Dade", "US-GA")).isTrue();
    assertThat(CrossStatePurchase.differs("US-NC", "US-NC")).isFalse();
    assertThat(CrossStatePurchase.differs("US-NC", null)).isFalse();
    assertThat(CrossStatePurchase.differs(null, "US-TX")).isFalse();
    assertThat(CrossStatePurchase.differs("SYNTHETIC-FIXTURE", "US-TX")).isFalse();
  }
}
