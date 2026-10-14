package com.dealanalyzer.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuoteRequestTextTest {
  @Test void aListingLinkIsCitedWhenThereIsOne() {
    assertThat(QuoteRequestText.render("1HGCM82633A004352", "https://dealer.example/vehicle/42"))
        .contains("VIN 1HGCM82633A004352 (listing: https://dealer.example/vehicle/42)");
  }

  @Test void aSavedAdWithNoLinkNamesTheCarByVinAlone() {
    assertThat(QuoteRequestText.render("1HGCM82633A004352", null)).isEqualTo(
        "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352, including the selling price, "
            + "taxes, all fees and all add-ons?");
  }

  @Test void anUnsupportedLinkOrVinIsStillRefused() {
    assertThatThrownBy(() -> QuoteRequestText.render("1HGCM82633A004352", "javascript:unsupported"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QuoteRequestText.render("NOT-A-VIN", null)).isInstanceOf(IllegalArgumentException.class);
  }
}
