package com.dealanalyzer.economics.fees;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Ticket 139: label normalisation, abbreviations and small misspellings. */
class FeeLabelsTest {
  @Test void abbreviationsCloseUpAndWordsSplitOnPunctuation() {
    assertThat(FeeLabels.tokens("T.T.&L.")).containsExactly("ttl");
    assertThat(FeeLabels.tokens("D.M.V. Fees")).containsExactly("dmv", "fees");
    assertThat(FeeLabels.tokens("Tag&Title/Registration")).containsExactly("tag", "and", "title", "registration");
    assertThat(FeeLabels.tokens("E-filing fee")).containsExactly("e", "filing", "fee");
    assertThat(FeeLabels.tokens("Gov't")).containsExactly("govt");
    assertThat(FeeLabels.tokens("Régistration")).containsExactly("registration");
  }

  @Test void tokensMatchByPrefixAbbreviationOrOneOrTwoEdits() {
    assertThat(FeeLabels.token("registrations", "registration")).isTrue();
    assertThat(FeeLabels.token("regist", "registration")).isTrue();
    assertThat(FeeLabels.token("registeration", "registration")).isTrue();
    assertThat(FeeLabels.token("liscense", "license")).isTrue();
    assertThat(FeeLabels.token("titel", "title")).isTrue();
    assertThat(FeeLabels.token("tire", "title")).isFalse();
    assertThat(FeeLabels.token("state", "plate")).isFalse();
    assertThat(FeeLabels.token("ev", "evidence")).isFalse();
    assertThat(FeeLabels.token("tt", "ttl")).isFalse();
  }

  @Test void multiWordTermsMatchInOrder() {
    assertThat(FeeLabels.matches("Hwy. use tax", List.of("hwy use tax"))).isTrue();
    assertThat(FeeLabels.matches("Highway Use Tax (HUT)", List.of("highway use tax"))).isTrue();
    assertThat(FeeLabels.matches("Tax use highway", List.of("highway use tax"))).isFalse();
  }
}
