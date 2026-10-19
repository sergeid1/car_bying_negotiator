package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpClientErrorException;

/** Ticket 142: named add-ons in the Desk Check, the first look at the dealer, the Deal Review and the report. */
class DealerAddOnAcceptanceTest {
  @TempDir Path directory;
  static volatile boolean addOnPaperwork;

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider addOnProvider() {
      var fixture = new AgreedPriceAcceptanceTest.Configuration().itemizedProvider();
      return document -> {
        var result = fixture.extract(document);
        var lines = new ArrayList<>(result.lineItems());
        if (addOnPaperwork) {
          var region = lines.getLast().evidence();
          lines.add(new LineItem("VIN ETCH", "ADD_ON", 29_900, "USD", "TAXABLE", "UNKNOWN", region, .97));
          lines.add(new LineItem("N2 Tires", "ADD_ON", 19_900, "USD", "TAXABLE", "UNKNOWN", region, .97));
          // A model-backed reader's suggestion; it names nothing until the Buyer confirms it.
          lines.add(new LineItem("Gold Package", "ADD_ON", 99_500, "USD", "TAXABLE", "UNKNOWN", region, .97,
              "paint-protection"));
        }
        return new ProviderExtraction(result.extractorVersion(), result.modelVersion(), result.schemaVersion(),
            result.expectedPageCount(), result.candidates(), lines, result.documentWarnings());
      };
    }
  }

  @Test void namedAddOnsReachTheDeskCheckTheFirstLookTheDealReviewAndTheReport() throws Exception {
    addOnPaperwork = false;
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("add-ons", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      addOnPaperwork = true;
      String document = DeskCheckAcceptanceTest.upload(app, base, token, "add-on-paperwork");

      // The verification step shows the suggestion for the Buyer to confirm or change.
      var extraction = get(client, base + "/documents/" + document + "/extractions/latest", token);
      var gold = find(extraction.path("lineItems"), "originalLabel", "Gold Package");
      assertThat(gold.at("/suggestedCharge/name").asText()).isEqualTo("paint protection");
      assertThat(gold.path("category").asText()).isEqualTo("ADD_ON");
      assertThat(find(extraction.path("lineItems"), "originalLabel", "VIN ETCH").path("suggestedCharge").isNull()).isTrue();

      DeskCheckAcceptanceTest.verify(client, base, token, document, "desk", 2680000);
      var desk = get(client, base + "/desk-check", token);
      assertThat(added(desk, "VIN ETCH").path("chargeName").asText()).isEqualTo("VIN etching");
      assertThat(added(desk, "N2 Tires").path("chargeName").asText()).isEqualTo("nitrogen-filled tires");
      assertThat(added(desk, "Gold Package").path("chargeName").isNull()).as("an unconfirmed suggestion names nothing").isTrue();
      var addOns = desk.path("addOns");
      assertThat(line(addOns, "VIN ETCH").path("kindWording").asText()).isEqualTo("Usually optional and negotiable.");
      assertThat(line(addOns, "Gold Package").path("status").asText()).isEqualTo("UNRECOGNISED");
      assertThat(line(addOns, "Gold Package").path("note").asText()).isEqualTo("Unrecognised charge — ask what it is.");
      assertThat(addOns.path("optionalTotalMinor").asLong()).isEqualTo(49_800);
      assertThat(addOns.path("summary").asText()).contains("Usually optional items total $498", "1 unrecognised charge");

      var first = post(client, base + "/desk-start/result", Map.of("documentId", document, "route", "AGREED_CAR"), "first", token);
      assertThat(first.path("findings").findValuesAsText("summary"))
          .anySatisfy(copy -> assertThat(copy).contains("Added: VIN ETCH (VIN etching), $299"));
      assertThat(line(first.path("addOns"), "N2 Tires").path("name").asText()).isEqualTo("nitrogen-filled tires");

      post(client, base + "/cash-economics", Map.of(), "cash", token);
      post(client, base + "/analyses", Map.of(), "analysis", token);
      var review = get(client, base + "/deal-reviews/latest", token);
      assertThat(line(review.path("dealerAddOns"), "VIN ETCH").path("name").asText()).isEqualTo("VIN etching");
      var report = get(client, base + "/reports/latest", token);
      var reported = report.at("/analysis/dealerAddOns");
      assertThat(line(reported, "VIN ETCH").path("question").asText()).contains("etched");
      assertThat(line(reported, "N2 Tires").path("verified").asBoolean()).isTrue();
      assertThat(line(reported, "Gold Package").path("status").asText()).isEqualTo("UNRECOGNISED");
      assertThat(reported.toString()).doesNotContain("paint protection");

      // Confirming a line with no suggestion is refused; confirming the suggestion names the line from then on.
      String extractionId = extraction.path("id").asText();
      List<String> candidates = new ArrayList<>(), lineIds = new ArrayList<>();
      extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
      extraction.path("lineItems").forEach(value -> lineIds.add(value.path("id").asText()));
      String etch = find(extraction.path("lineItems"), "originalLabel", "VIN ETCH").path("id").asText();
      assertThatThrownBy(() -> post(client, base + "/dealer-offers", Map.of("extractionId", extractionId,
          "confirmedCandidateIds", candidates, "confirmedLineItemIds", lineIds,
          "confirmedSuggestionLineItemIds", List.of(etch)), "no-suggestion", token))
          .isInstanceOf(HttpClientErrorException.class);
      var confirmed = post(client, base + "/dealer-offers", Map.of("extractionId", extractionId,
          "confirmedCandidateIds", candidates, "confirmedLineItemIds", lineIds,
          "confirmedSuggestionLineItemIds", List.of(gold.path("id").asText())), "suggestion", token);
      var goldTerm = find(confirmed.at("/terms/lineItems"), "originalLabel", "Gold Package");
      assertThat(goldTerm.path("chargeId").asText()).isEqualTo("paint-protection");
      assertThat(goldTerm.path("category").asText()).isEqualTo("ADD_ON");
      var after = get(client, base + "/desk-check", token);
      assertThat(line(after.path("addOns"), "Gold Package").path("status").asText()).isEqualTo("RECOGNISED");
      assertThat(line(after.path("addOns"), "Gold Package").path("confirmedByBuyer").asBoolean()).isTrue();
      assertThat(added(after, "Gold Package").path("chargeName").asText()).isEqualTo("paint protection");
      assertThat(after.at("/addOns/optionalTotalMinor").asLong()).isEqualTo(149_300);
    }
  }

  private static JsonNode added(JsonNode desk, String label) {
    for (var finding : desk.path("findings"))
      if ("ADDED".equals(finding.path("group").asText()) && label.equals(finding.path("label").asText())) return finding;
    throw new AssertionError("No added finding for " + label + " in " + desk.path("findings"));
  }

  private static JsonNode line(JsonNode check, String label) {
    for (var line : check.path("lines")) if (label.equals(line.path("label").asText())) return line;
    throw new AssertionError("No add-on line for " + label + " in " + check);
  }
}
