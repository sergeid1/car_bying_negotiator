package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Ticket 141: a North Carolina registration with a dealer in Texas (a pack) or South Carolina (no pack) gets the
 * report as usual, with the dealer's $777 tax as written and no tax calculation. Labelled synthetic paperwork.
 */
class CrossStatePurchaseAcceptanceTest {
  @TempDir Path directory;

  @Test void aDealerInAnotherStateGetsTheReportWithTheDealersTaxAsWritten() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("cross-state-purchase", true,
        FixtureExtractionProvider.class, "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();

      // Same state: unchanged from ticket 137, 3% of the $25,399 taxable price.
      var sameState = report(client, app, "Raleigh", "NC", "same");
      assertThat(sameState.cash().at("/result/taxCalculation/status").asText()).isEqualTo("CALCULATED");
      assertThat(sameState.cash().at("/result/taxes/minorUnits").asLong()).isEqualTo(76_197);
      assertThat(sameState.report().at("/analysis/limitations").toString()).doesNotContain("Tax is not checked");

      for (String dealerState : new String[] {"TX", "SC"}) {
        var crossState = report(client, app, dealerState.equals("TX") ? "Dallas" : "Myrtle Beach", dealerState,
            "cross-" + dealerState);
        var tax = crossState.cash().at("/result/taxCalculation");
        assertThat(crossState.cash().path("policyVersion").asText()).as("registration state's pack, for the record")
            .isEqualTo("US-NC.cash.2026.v1");
        assertThat(tax.path("status").asText()).isEqualTo("NOT_CHECKED");
        assertThat(tax.path("dealerState").asText()).isEqualTo("US-" + dealerState);
        assertThat(tax.path("components")).isEmpty();
        assertThat(crossState.cash().at("/result/taxes/minorUnits").asLong()).as("the dealer's tax total as written")
            .isEqualTo(77_700);
        assertThat(crossState.cash().at("/result/reconciliation/reason").asText()).contains("dealer's tax as written");
        assertThat(crossState.report().at("/analysis/limitations").toString())
            .contains("Tax is not checked because the dealer is in a different state")
            .doesNotContain("highway use tax when the car is titled");
      }
    }
  }

  private record Opened(JsonNode cash, JsonNode report) {}

  private static Opened report(RestClient client, RunningApplication app, String dealerCity, String dealerState,
      String key) throws Exception {
    var request = new HashMap<>(validCase());
    request.remove("dealerPostalCode");
    request.put("dealerCity", dealerCity);
    request.put("dealerState", dealerState);
    var created = post(client, "/api/v1/cases", request, key + "-case");
    assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-NC");
    String id = created.at("/case/id").asText(), token = created.path("accessToken").asText(), base = "/api/v1/cases/" + id;
    post(client, base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
        Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000)), key + "-vehicle", token);
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var upload = new ObjectMapper().readTree(client.post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key + "-upload").header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    awaitExtraction(app, upload.at("/operation/id").asText(), token);
    String document = base + "/documents/" + upload.at("/document/id").asText();
    var extraction = get(client, document + "/extractions/latest", token);
    String corrections = document + "/extractions/" + extraction.path("id").asText() + "/corrections";
    post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId",
        find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
        "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), key + "-financed", token);
    post(client, corrections, Map.of("targetType", "LINE_ITEM",
        "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), key + "-fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), key + "-verified", token);
    var cash = post(client, base + "/cash-economics", Map.of(), key + "-cash", token);
    post(client, base + "/market-assessments", Map.of(), key + "-market", token);
    post(client, base + "/analyses", Map.of(), key + "-analysis", token);
    return new Opened(cash, get(client, base + "/reports/latest", token));
  }
}
