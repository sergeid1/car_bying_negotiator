package com.dealanalyzer.recovery;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.ai.ProhibitedClaims;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SpanishEmailTest {
  @ParameterizedTest @EnumSource(RecoveryEmail.Purpose.class)
  void everyPurposeHasSpanishSubjectAndBodyAndPreservesItsLink(RecoveryEmail.Purpose purpose) {
    String link = "https://example.test/#recover=private-token";
    var content = SmtpRecoveryEmail.spanish(purpose, link);
    assertThat(content.subject()).isNotBlank().doesNotContain("Your", "update", "link");
    assertThat(content.body()).contains(link).doesNotContain("You requested", "Open your journey");
    assertThat(ProhibitedClaims.contains(content.subject() + " " + content.body(), "es")).isFalse();
  }
}
