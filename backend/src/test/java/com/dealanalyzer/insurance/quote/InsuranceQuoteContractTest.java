package com.dealanalyzer.insurance.quote;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.*;
import com.dealanalyzer.partner.PartnerOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A Buyer-entered quote may lack a link or expiry; a partner's quote that lacks either never reaches the Buyer. */
class InsuranceQuoteContractTest {
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final Provider PROVIDER = new Provider("LICENSED_TEST", "v1", "TEST_EXECUTED_TERMS", false);
  private static final CoverageSet COVERAGE = CoverageSet.of(new Coverage(Coverage.Type.COLLISION, null, null, 50000L));

  private static Result result(InsuranceQuote quote) {
    return new Result(Availability.AVAILABLE, null, PROVIDER, NOW,
        List.of(new InsurerEntry("Insurer", PartnerOutcome.OFFERED, quote)), BigDecimal.ZERO);
  }

  @Test void aPartnerQuoteNeedsItsExpiryAndContinueLink() {
    var link = URI.create("https://partner.example/continue");
    assertThat(InsuranceQuoteContract.failure(result(new InsuranceQuote(100000, "USD", Term.TWELVE_MONTHS, COVERAGE, NOW,
        NOW.plusSeconds(3600), link)), PROVIDER, NOW)).isNull();
    assertThat(InsuranceQuoteContract.failure(result(new InsuranceQuote(100000, "USD", Term.TWELVE_MONTHS, COVERAGE, NOW,
        null, link)), PROVIDER, NOW)).isEqualTo(Reason.INVALID_RESPONSE);
    assertThat(InsuranceQuoteContract.failure(result(new InsuranceQuote(100000, "USD", Term.TWELVE_MONTHS, COVERAGE, NOW,
        NOW.plusSeconds(3600), null)), PROVIDER, NOW)).isEqualTo(Reason.INVALID_RESPONSE);
  }

  @Test void aBuyerEnteredQuoteMayLackALinkOrExpiryButNotAValidOne() {
    assertThatCode(() -> new InsuranceQuote(100000, "USD", Term.SIX_MONTHS, COVERAGE, NOW, null, null)).doesNotThrowAnyException();
    assertThatThrownBy(() -> new InsuranceQuote(100000, "USD", Term.SIX_MONTHS, COVERAGE, NOW, NOW.minusSeconds(1), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InsuranceQuote(100000, "USD", Term.SIX_MONTHS, COVERAGE, NOW, null, URI.create("http://insecure.example")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
