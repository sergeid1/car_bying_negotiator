package com.dealanalyzer.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import com.dealanalyzer.finance.FinancingCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic development checks; real extraction accuracy belongs to the permissioned corpus. */
class FourSquareExtractionTest {
  @Test void syntheticLayoutsPreserveShownValuesAndFlagMissingAndUncertainTerms() throws Exception {
    var json = new ObjectMapper();
    var corpus = json.readTree(getClass().getResourceAsStream("/fixtures/extraction/four-square-corpus.json"));
    assertThat(corpus.path("kind").asText()).isEqualTo("synthetic");
    for (var layout : corpus.path("layouts")) {
      var extracted = DeterministicDealerDocumentTestSupport.extract(layout.path("pages"));
      assertThat(extracted.documentWarnings()).as(layout.path("name").asText()).isEmpty();
      var actual = json.createObjectNode();
      extracted.candidates().forEach(candidate -> {
        actual.put(candidate.field(), candidate.normalizedValue());
        assertThat(candidate.originalWording()).isNotBlank();
        assertThat(candidate.evidence().pageNumber()).isEqualTo(1);
      });
      assertThat(actual).as(layout.path("name").asText()).isEqualTo(layout.path("expected"));
      var validated = new DealTermsValidator(0.85).validate(extracted, 1, false);
      assertThat(validated.issueCodes()).containsExactlyInAnyOrderElementsOf(
          json.convertValue(layout.path("issueCodes"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
      for (var field : layout.path("reviewFields")) {
        assertThat(validated.candidates()).filteredOn(c -> c.field().equals(field.asText()))
            .singleElement().satisfies(c -> {
              assertThat(c.confidence()).isLessThanOrEqualTo(0.5);
              assertThat(c.verificationState()).isIn("REQUIRES_REVIEW", "MISSING");
            });
      }
    }
  }

  @Test void theTradeVinIsReadSeparatelyFromTheVehicleVinAndChecksumValidated() {
    var pages = new ObjectMapper().createArrayNode().add(
        "FOUR-SQUARE\nVIN: 1HGCM82633A004352\nTrade VIN: 5J6RW2H52ML000002\nTrade Allowance: $11,500\nTrade Payoff: $19,200");
    var extracted = DeterministicDealerDocumentTestSupport.extract(pages);
    assertThat(extracted.candidates()).filteredOn(c -> c.field().equals("VEHICLE_VIN")).singleElement()
        .satisfies(c -> assertThat(c.normalizedValue()).isEqualTo("1HGCM82633A004352"));
    assertThat(extracted.candidates()).filteredOn(c -> c.field().equals("TRADE_VIN")).singleElement()
        .satisfies(c -> assertThat(c.normalizedValue()).isEqualTo("5J6RW2H52ML000002"));
    assertThat(new DealTermsValidator(0.85).validate(extracted, 1, false).issueCodes()).doesNotContain("INVALID_VIN");
    pages.set(0, new ObjectMapper().getNodeFactory().textNode("FOUR-SQUARE\nTrade VIN: 5J6RW2H52ML000003"));
    assertThat(new DealTermsValidator(0.85).validate(DeterministicDealerDocumentTestSupport.extract(pages), 1, false)
        .issueCodes()).contains("INVALID_VIN");
  }

  @Test void handwrittenProviderOutputCannotBypassReviewEvenAtALowConfiguredThreshold() {
    var candidate = new Candidate("MONTHLY_PAYMENT", "Payment: $399 [handwritten]", "39900", "USD_MINOR",
        new EvidenceRegion(1, 0, 0, 1, 1), 0.99, false);
    var result = new DealTermsValidator(0.2).validate(new ProviderExtraction(
        "synthetic.v1", "none", "deal-terms.v1", 1, List.of(candidate), List.of(), List.of()), 1, false);
    assertThat(result.candidates().getFirst().confidence()).isEqualTo(0.5);
    assertThat(result.candidates().getFirst().verificationState()).isEqualTo("REQUIRES_REVIEW");
  }

  @Test void completeWorksheetUsesTheFinancingEnginesRoundingAndTolerance() {
    var calculator = new FinancingCalculator();
    var expected = calculator.reconcilePayment(2_480_000L, new BigDecimal("7.5"), 60, 49_696L);
    assertThat(expected.withinTolerance()).isTrue();
    assertThat(expected.paymentMinor()).isEqualTo(49_694L);
    var pages = new ObjectMapper().createArrayNode().add("FOUR-SQUARE\nAmount Financed: $24,800\nAPR: 7.5%\nTerm: 60 months\nPayment: $497.94\nOut the Door Total: $26,800");
    var provider = DeterministicDealerDocumentTestSupport.extract(pages);
    assertThat(new DealTermsValidator(0.85).validate(provider, 1, false).issueCodes()).isEmpty();
    pages.set(0, new ObjectMapper().getNodeFactory().textNode(pages.get(0).asText().replace("$497.94", "$497.95")));
    assertThat(new DealTermsValidator(0.85).validate(DeterministicDealerDocumentTestSupport.extract(pages), 1, false)
        .issueCodes()).contains("FINANCING_INCONSISTENT");
    assertThat(calculator.reconcilePayment(null, new BigDecimal("7.5"), 60, 49_696L).paymentMinor()).isNull();
  }
}
