package com.dealanalyzer.buyingcase;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseAccessServiceTest {
  @Test
  void rejectsAnAccessPepperShorterThan256Bits() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new CaseAccessService("too-short", Duration.ofHours(1)))
        .withMessageContaining("at least 32 bytes");
  }
}
