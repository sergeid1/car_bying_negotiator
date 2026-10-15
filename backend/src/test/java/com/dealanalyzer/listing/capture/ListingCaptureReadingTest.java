package com.dealanalyzer.listing.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.ai.ModelRolloutGate;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import com.dealanalyzer.listing.ListingEvidenceProvider.ListingRetrieval;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedDealer;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import com.dealanalyzer.listing.ListingProviderContract;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Capture;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Field;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Reading;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.vehicle.VehicleEvidenceService.DealerView;
import com.dealanalyzer.vehicle.VehicleEvidenceService.ListingSnapshotView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Ticket 115: reading the labelled synthetic listing captures, and the listing price check over them. */
class ListingCaptureReadingTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PAPERWORK_VIN = "1HGCM82633A004352";

  static JsonNode fixtures() throws Exception {
    try (var input = ListingCaptureReadingTest.class.getResourceAsStream("/fixtures/listing/listing-captures.v1.json")) {
      return JSON.readTree(input).path("fixtures");
    }
  }

  static Reading read(String text) {
    var page = new NormalizedDocumentPage(1, new byte[0], text, false, false, false, false);
    return ListingCaptureValidator.validate(
        new DeterministicListingCaptureProvider().read(new Capture("case", "document", true, List.of(page))), 1);
  }

  @Test void eachLabelledFixtureReadsAsExpected() throws Exception {
    assertThat(fixtures()).hasSize(5);
    for (JsonNode fixture : fixtures()) {
      var expected = fixture.path("expectedReading");
      var reading = read(fixture.path("text").asText());
      String status = reading.fields().stream().anyMatch(field -> field.value() != null) ? "READ" : "UNREADABLE";
      assertThat(status).as(fixture.path("label").asText()).isEqualTo(expected.path("status").asText());
      for (String field : ListingCaptureProvider.FIELDS) {
        if (!expected.has(field)) continue;
        assertThat(value(reading, field)).as(fixture.path("label").asText() + " " + field).isEqualTo(expected.path(field).asText());
      }
      assertThat(reading.fields().stream().filter(field -> field.field().equals("LISTING_PRICE_CONDITIONS")).count())
          .as(fixture.path("label").asText() + " conditions").isEqualTo(expected.path("conditions").asLong());
      if (status.equals("UNREADABLE")) assertThat(reading.warnings()).isNotEmpty();
    }
  }

  @Test void pastedTextFieldsPointAtTheLineTheyWereReadFrom() {
    var reading = read("2003 Honda Accord EX\nSale Price: $23,995\nVIN: 1HGCM82633A004352");
    var price = reading.fields().stream().filter(field -> field.field().equals("LISTING_PRICE")).findFirst().orElseThrow();
    var vin = reading.fields().stream().filter(field -> field.field().equals("VEHICLE_VIN")).findFirst().orElseThrow();
    assertThat(price.originalWording()).isEqualTo("Sale Price: $23,995");
    assertThat(price.evidence().pageNumber()).isEqualTo(1);
    assertThat(price.evidence().height()).isLessThan(0.05);
    assertThat(vin.evidence().y()).isGreaterThan(price.evidence().y());
    // A labelled price wins over an unlabelled amount, and an illegible VIN is never guessed.
    assertThat(value(read("$19,999\nPrice: $21,500"), "LISTING_PRICE")).isEqualTo("2150000");
    assertThat(value(read("VIN: 1HGCM82633A004353"), "VEHICLE_VIN")).isNull();
  }

  @Test void theValidatorRefusesOutputOutsideTheSchemaAndBlanksImpossibleValues() {
    var region = new EvidenceRegion(1, 0.1, 0.1, 0.5, 0.05);
    var bad = new Reading("x", "none", ListingCaptureValidator.SCHEMA_VERSION,
        List.of(new Field("SELLING_PRICE", "Price $1", "100", "USD_MINOR", region, 0.9)), List.of());
    assertThatThrownBy(() -> ListingCaptureValidator.validate(bad, 1)).isInstanceOf(IllegalArgumentException.class);
    var offPage = new Reading("x", "none", ListingCaptureValidator.SCHEMA_VERSION,
        List.of(new Field("LISTING_PRICE", "Price $1", "100", "USD_MINOR", new EvidenceRegion(2, 0, 0, 1, 1), 0.9)), List.of());
    assertThatThrownBy(() -> ListingCaptureValidator.validate(offPage, 1)).isInstanceOf(IllegalArgumentException.class);
    var impossible = ListingCaptureValidator.validate(new Reading("x", "none", ListingCaptureValidator.SCHEMA_VERSION,
        List.of(new Field("VEHICLE_YEAR", "Year 1890", "1890", "YEAR", region, 0.9)), List.of()), 1);
    assertThat(impossible.fields().getFirst().value()).isNull();
    assertThat(impossible.fields().getFirst().confidence()).isZero();
  }

  @Test void thePriceCheckSaysWhatTheAdAndThePaperworkShowAndAsksOneQuestion() throws Exception {
    for (JsonNode fixture : fixtures()) {
      var reading = read(fixture.path("text").asText());
      var expected = fixture.path("expectedCheck");
      String price = expected.has("typedPriceMinor") ? expected.path("typedPriceMinor").asText() : value(reading, "LISTING_PRICE");
      var conditions = reading.fields().stream().filter(field -> field.field().equals("LISTING_PRICE_CONDITIONS"))
          .map(Field::value).toList();
      var listing = snapshot(Long.parseLong(price), value(reading, "VEHICLE_VIN"), conditions, LocalDate.of(2026, 10, 1));
      var result = ListingPriceCheck.compare(listing, paperwork(2_500_000), "DESK_PAPERWORK", false);
      assertThat(result.status()).as(fixture.path("label").asText()).isEqualTo(expected.path("status").asText());
      if (expected.has("differenceMinor"))
        assertThat(result.differenceMinor()).as(fixture.path("label").asText()).isEqualTo(expected.path("differenceMinor").asLong());
      assertThat(result.priceConditions()).containsExactlyElementsOf(conditions);
      assertThat(result.policyVersion()).isEqualTo("listing-price-check.v1");
    }
    var higher = ListingPriceCheck.compare(snapshot(2_399_500, PAPERWORK_VIN, List.of(), LocalDate.of(2026, 10, 1)),
        paperwork(2_500_000), "DEALER_OFFER", true);
    assertThat(higher.deskQuestion())
        .isEqualTo("The listing on October 1, 2026 showed $23,995. Why is the price on this paperwork $25,000?");
    assertThat(higher.summary()).contains("$1,005 more", "listing you saved");
    var same = ListingPriceCheck.compare(snapshot(2_500_000, PAPERWORK_VIN, List.of(), null), paperwork(2_500_000), "DEALER_OFFER", true);
    assertThat(same.summary()).contains("is the same as the listing you saved");
    assertThat(same.deskQuestion()).isNull();
    var lower = ListingPriceCheck.compare(snapshot(2_600_000, PAPERWORK_VIN, List.of(), null), paperwork(2_500_000), "DEALER_OFFER", true);
    assertThat(lower.status()).isEqualTo("LOWER");
    assertThat(lower.summary()).contains("$1,000 less than the $26,000");
    var none = ListingPriceCheck.compare(snapshot(2_500_000, PAPERWORK_VIN, List.of(), null), JSON.readTree("{\"candidates\":[]}"),
        "DESK_PAPERWORK", false);
    assertThat(none.status()).isEqualTo("NO_PAPERWORK_PRICE");
    for (var result : List.of(higher, same, lower, none))
      assertThat(result.summary() + " " + result.deskQuestion()).doesNotContainIgnoringCase("illegal").doesNotContainIgnoringCase("law")
          .doesNotContainIgnoringCase("bait").doesNotContainIgnoringCase("checked the dealer");
  }

  @Test void aConfirmedCaptureSatisfiesTheListingProviderContract() {
    var now = Instant.now();
    var listing = new NormalizedListing("capture:document", null, PAPERWORK_VIN, 2003, "Honda", "Accord", "EX", 98_200L,
        2_399_500L, "USD", new NormalizedDealer("capture:north-branch-motors", "North Branch Motors", null, null),
        ListingEvidencePersistence.BUYER_CAPTURE, now, DeterministicListingCaptureProvider.VERSION, ListingCaptureService.LICENSE,
        "source-document:document");
    assertThat(ListingProviderContract.valid(ListingRetrieval.supported(listing), now)).isTrue();
    assertThat(ListingCaptureService.LICENSE).contains("Buyer's own capture").contains("not redistributed");
  }

  @Test void selectingClaudeRequiresTheListingPromptsOwnShadowComparison() {
    var task = ClaudeListingCaptureProvider.task();
    assertThat(task.promptVersion()).isEqualTo("listing-capture-prompt.v1");
    assertThat(task.schemaVersion()).isEqualTo("listing-capture.v1");
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> JSON)
        .withUserConfiguration(ModelRolloutGate.class, ListingCaptureProviderConfiguration.class)
        .withPropertyValues("extraction.provider=claude")
        .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
            .hasMessageContaining("NO_SHADOW_COMPARISON:ANTHROPIC:EXTRACTION:listing-capture-prompt.v1:listing-capture.v1"));
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> JSON)
        .withUserConfiguration(ModelRolloutGate.class, ListingCaptureProviderConfiguration.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ListingCaptureProvider.class)).isInstanceOf(DeterministicListingCaptureProvider.class);
        });
    var parsed = ClaudeListingCaptureProvider.parse(JSON.valueToTree(java.util.Map.of(
        "fields", List.of(java.util.Map.of("field", "LISTING_PRICE", "originalWording", "Price $23,995",
            "normalizedValue", "2399500", "normalizedUnit", "USD_MINOR", "confidence", 0.97,
            "evidence", java.util.Map.of("pageNumber", 1, "x", 0.1, "y", 0.2, "width", 0.3, "height", 0.05))),
        "documentWarnings", List.of())));
    var reading = ListingCaptureValidator.validate(ClaudeListingCaptureProvider.reading(parsed, "claude-opus-5-5"), 1);
    assertThat(reading.extractorVersion()).isEqualTo("claude:listing-capture-prompt.v1");
    assertThat(reading.fields().getFirst().value()).isEqualTo("2399500");
  }

  private static String value(Reading reading, String field) {
    return reading.fields().stream().filter(item -> item.field().equals(field)).findFirst().map(Field::value).orElse(null);
  }

  private static JsonNode paperwork(long sellingPriceMinor) throws Exception {
    return JSON.readTree("{\"candidates\":[{\"field\":\"SELLING_PRICE\",\"value\":\"" + sellingPriceMinor
        + "\",\"unit\":\"USD_MINOR\"},{\"field\":\"VEHICLE_VIN\",\"value\":\"" + PAPERWORK_VIN + "\",\"unit\":\"VIN\"}]}");
  }

  private static ListingSnapshotView snapshot(long priceMinor, String vin, List<String> conditions, LocalDate capturedOn) {
    return new ListingSnapshotView("snapshot", null, vin, 2003, "Honda", "Accord", "EX", 98_200L, priceMinor, "USD",
        ListingEvidencePersistence.BUYER_CAPTURE, Instant.parse("2026-10-02T15:00:00Z"), "v", ListingCaptureService.LICENSE,
        new DealerView("dealer", "North Branch Motors", null, null), capturedOn, conditions.isEmpty() ? null : conditions, "document");
  }
}
