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
 * Ticket 137: a Texas and a North Carolina Buyer each open a case and reach a report without being asked for
 * a county, under the sourced 2026 packs. Labelled synthetic ZIP codes, paperwork and prices.
 */
class TexasNorthCarolinaIntakeAcceptanceTest {
  @TempDir Path directory;

  @Test void texasAndNorthCarolinaCasesReachAReportWithoutACountyAndOtherStatesAreStillRefused() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("texas-north-carolina-intake", true,
        FixtureExtractionProvider.class, "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      assertThat(client.get().uri("/api/v1/intake-options").retrieve().body(JsonNode.class).path("supported").toString())
          .isEqualTo("[\"US-FL\",\"US-NC\",\"US-TX\"]");

      // Synthetic $24,500 vehicle subtotal plus the $899 dealer fee, no trade-in: one state component each.
      var texas = report(client, app, "75201", "texas");
      assertThat(texas.cash().path("policyVersion").asText()).isEqualTo("US-TX.cash.2026.v1");
      assertThat(texas.cash().at("/result/taxCalculation/components/0/name").asText()).isEqualTo("Texas motor vehicle sales tax");
      assertThat(texas.cash().at("/result/taxCalculation/taxName").asText()).isEqualTo("Texas motor vehicle sales tax");
      assertThat(texas.cash().at("/result/taxes/minorUnits").asLong()).isEqualTo(158_744); // 6.25% of $25,399
      assertThat(texas.report().at("/analysis/limitations").toString()).contains("US-TX using US-TX.cash.2026.v1")
          .doesNotContain("titled");

      var carolina = report(client, app, "27601", "carolina");
      assertThat(carolina.cash().path("policyVersion").asText()).isEqualTo("US-NC.cash.2026.v1");
      assertThat(carolina.cash().at("/result/taxCalculation/components/0/name").asText()).isEqualTo("North Carolina highway use tax");
      assertThat(carolina.cash().at("/result/taxes/minorUnits").asLong()).isEqualTo(76_197); // 3% of $25,399
      assertThat(carolina.report().at("/analysis/limitations").toString())
          .contains("US-NC using US-NC.cash.2026.v1", "highway use tax when the car is titled")
          .doesNotContain("synthetic development fixture");

      // A state with no pack keeps the existing refusal, and nothing about it is stored.
      var refused = client.post().uri("/api/v1/cases").contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "georgia").body(intake("30303"))
          .exchange((sent, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            return new ObjectMapper().readTree(response.getBody());
          });
      assertThat(refused.path("reason").asText()).isEqualTo("JURISDICTION_NOT_SUPPORTED");
      assertThat(refused.path("detail").asText()).contains("does not support purchases in that jurisdiction yet");
      assertThat(refused.path("candidateJurisdictions").toString()).isEqualTo("[\"US-FL\",\"US-NC\",\"US-TX\"]");

      // A ZIP code that crosses into North Carolina still asks for the state, not a county.
      var crossing = client.post().uri("/api/v1/cases").contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "crossing").body(intake("27048"))
          .exchange((sent, response) -> new ObjectMapper().readTree(response.getBody()));
      assertThat(crossing.path("reason").asText()).isEqualTo("POSTAL_CODE_CROSSES_STATE_LINE");
      assertThat(crossing.path("field").asText()).isEqualTo("buyerJurisdiction");
      var stated = new HashMap<>(intake("27048"));
      stated.put("buyerJurisdiction", "US-NC");
      assertThat(post(client, "/api/v1/cases", stated, "crossing-stated").at("/case/buyerJurisdiction").asText())
          .isEqualTo("US-NC");
    }
  }

  private record Opened(JsonNode cash, JsonNode report) {}

  private static Opened report(RestClient client, RunningApplication app, String zip, String key) throws Exception {
    var created = post(client, "/api/v1/cases", intake(zip), key + "-case");
    String state = zip.startsWith("75") ? "US-TX" : "US-NC";
    assertThat(created.at("/case/buyerJurisdiction").asText()).as("no county is asked for or stored").isEqualTo(state);
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

  private static Map<String, Object> intake(String zip) {
    var request = new HashMap<>(validCase());
    request.put("buyerPostalCode", zip);
    request.put("dealerPostalCode", zip);
    return request;
  }
}
