package com.dealanalyzer.purchase;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SampleReportRedactionTest {
  @Test void shippedSampleInputIsTheExistingSyntheticGoldenFixture() throws Exception {
    var json = new ObjectMapper();
    try (var sample = getClass().getResourceAsStream("/purchase/sample-golden.v1.json");
         var golden = getClass().getResourceAsStream("/fixtures/golden/cash-deal-standard.json")) {
      assertThat(json.readTree(sample)).isEqualTo(json.readTree(golden).path("input"));
    }
  }
  @Test void redactionRemovesSourceTextAndSensitiveFieldsWhilePreservingGeneratedCopyAndNumbers() throws Exception {
    var json = new ObjectMapper();
    var source = json.readTree("""
        {"vin":"SECRET-VIN","dealerIdentity":{"name":"SECRET-DEALER"},"listingUrl":"SECRET-URL",
         "buyerZip":"SECRET-ZIP","buyerLocation":{"zip":"SECRET-ZIP"},
         "evidence":[{"documentId":"SECRET-DOCUMENT","pageNumber":7,"region":{"x":42},"originalWording":"SECRET-TEXT"}],
         "documentReferences":["SECRET-REFERENCE"],"region_x":42,
         "notes":["SECRET-NOTE"],"raw_document_text":"SECRET-RAW","listingTitle":"SECRET-TITLE",
         "topReasons":["Generated reason"],"findings":[{"label":"Generated label","ratingBasis":"Generated basis"}],
         "nextAction":"Generated next action","limitations":["Generated limitation"],
         "amountMinor":123,"percentage":6.5,"apr":7.9,"termMonths":60,"amounts":[10,20],"currency":"USD"}
        """);
    var redacted = SampleReportController.redact(source);
    assertThat(redacted.toString()).doesNotContain("SECRET", "42");
    assertThat(redacted.at("/evidence/0/pageNumber").isNull()).isTrue();
    assertThat(redacted.path("documentReferences").isArray()).isTrue();
    assertThat(redacted.path("documentReferences")).isEmpty();
    assertThat(redacted.path("region_x").isNull()).isTrue();
    assertThat(redacted.at("/dealerIdentity/name").isNull()).isTrue();
    assertThat(redacted.path("amountMinor").asInt()).isEqualTo(123);
    for (String field : java.util.List.of("topReasons", "findings", "nextAction", "limitations", "percentage", "apr", "termMonths", "amounts"))
      assertThat(redacted.path(field)).as(field).isEqualTo(source.path(field));
    assertThat(redacted.path("currency").asText()).isEqualTo("USD");
    assertThat(source.path("vin").asText()).isEqualTo("SECRET-VIN");
  }
}
