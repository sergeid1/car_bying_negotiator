package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class DeskCheckAcceptanceTest {
  private static final String OTHER_VIN = "1M8GDM9AXKP042788";
  /** Which paperwork the dealer hands over next: the agreed quote, desk paperwork with an add-on, or another car. */
  static volatile String paperwork = "AGREED";

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider deskProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        var second = new EvidenceRegion(2, .08, .16, .55, .04);
        var candidates = new ArrayList<Candidate>();
        var lines = new ArrayList<>(extracted.lineItems());
        boolean desk = !paperwork.equals("AGREED");
        for (var candidate : extracted.candidates()) {
          if (desk && candidate.field().equals("OUT_THE_DOOR_TOTAL"))
            candidate = new Candidate("OUT_THE_DOOR_TOTAL", "Total $28,295", "2829500", "USD_MINOR", candidate.evidence(), .98, true);
          if (desk && candidate.field().equals("SUBTOTAL"))
            candidate = new Candidate("SUBTOTAL", "Subtotal $27,394", "2739400", "USD_MINOR", candidate.evidence(), .98, true);
          if (paperwork.equals("OTHER_CAR") && candidate.field().equals("VEHICLE_VIN"))
            candidate = new Candidate("VEHICLE_VIN", "VIN " + OTHER_VIN, OTHER_VIN, "VIN", candidate.evidence(), .98, true);
          candidates.add(candidate);
        }
        candidates.add(new Candidate("ADD_ON_TOTAL", desk ? "Add-ons $1,495" : "Add-ons $0", desk ? "149500" : "0", "USD_MINOR", second, .99, true));
        if (desk) lines.add(new LineItem("Service contract", "ADD_ON", 149_500, "USD", "NONTAXABLE", "OPTIONAL", second, .97));
        return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
            extracted.expectedPageCount(), candidates, lines, extracted.documentWarnings());
      };
    }
  }

  @TempDir Path directory;

  @Test void deskPaperworkLeadsWithTheAddedServiceContractAndAnotherCarStartsASeparateCase() throws Exception {
    paperwork = "AGREED";
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("desk", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var cityCase = new java.util.HashMap<String, Object>(validCase());
      cityCase.remove("dealerPostalCode"); cityCase.put("dealerCity", "Raleigh"); cityCase.put("dealerState", "NC");
      var created = post(client, "/api/v1/cases", cityCase, "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, desk = base + "/desk-check";

      assertThat(get(client, desk, token).path("status").asText()).isEqualTo("NO_AGREED_PRICE");
      assertThatThrownBy(() -> get(client, desk, "wrong")).isInstanceOf(HttpClientErrorException.class);
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      var waiting = get(client, desk, token);
      assertThat(waiting.path("status").asText()).isEqualTo("AWAITING_PAPERWORK");
      assertThat(waiting.at("/agreedPrice/outTheDoorMinor").asLong()).isEqualTo(2_680_000);
      assertThat(waiting.at("/agreedPrice/vin").asText()).isEqualTo("1HGCM82633A004352");

      // Scenario 23: the paperwork at the desk adds a $1,495 service contract.
      paperwork = "ADD_ON";
      String deskDocument = upload(app, base, token, "desk-1");
      var read = get(client, desk, token);
      assertThat(read.path("status").asText()).isEqualTo("DIFFERENCES");
      assertThat(read.path("source").asText()).isEqualTo("READ_FROM_PAPERWORK");
      var lead = read.at("/findings/0");
      assertThat(lead.path("group").asText()).isEqualTo("ADDED");
      assertThat(lead.path("label").asText()).isEqualTo("Service contract");
      assertThat(lead.at("/desk/amountMinor").asLong()).isEqualTo(149_500);
      assertThat(lead.path("verified").asBoolean()).as("terms as read are never reported as verified").isFalse();
      read.path("findings").forEach(finding -> assertThat(finding.path("verified").asBoolean()).isFalse());
      assertThat(read.at("/totalEffect/basis").asText()).isEqualTo("QUOTED");
      assertThat(read.at("/totalEffect/outTheDoorDeltaMinor").asLong()).isEqualTo(149_500);
      assertThat(read.at("/totalEffect/addedChargesMinor").asLong()).isEqualTo(149_500);
      assertThat(read.at("/paperwork/documentId").asText()).isEqualTo(deskDocument);
      // The re-read "Mystery protection" keeps its amount; its classification is shown but does not lead.
      assertThat(read.path("findings").findValuesAsText("label")).doesNotContain("Mystery protection");
      assertThat(read.path("otherDifferences").findValuesAsText("label")).contains("Mystery protection");

      // Verified through the existing workflow, the saved Deal Review against the Agreed Price drives the check.
      verify(client, base, token, deskDocument, "desk", 2_829_500);
      assertThat(get(client, desk, token).path("source").asText()).isEqualTo("VERIFIED_OFFER");
      post(client, base + "/cash-economics", Map.of(), "desk-cash", token);
      var report = post(client, base + "/analyses", Map.of(), "desk-analysis", token);
      assertThat(report.isObject()).isTrue();
      var reviewed = get(client, desk, token);
      var agreedPrice = get(client, base + "/quote-acceptance", token).path("agreedPrice");
      var review = get(client, base + "/deal-reviews/latest", token);
      assertThat(reviewed.path("source").asText()).isEqualTo("DEAL_REVIEW");
      assertThat(reviewed.path("dealReviewVersion").asInt()).isEqualTo(review.path("version").asInt());
      assertThat(review.path("baselineId").asText()).isEqualTo(agreedPrice.path("baselineId").asText());
      assertThat(reviewed.at("/findings/0/label").asText()).isEqualTo("Service contract");
      assertThat(reviewed.at("/findings/0/verified").asBoolean()).isTrue();
      assertThat(reviewed.at("/totalEffect/basis").asText()).isEqualTo("CALCULATED");
      assertThat(reviewed.at("/totalEffect/preTaxDeltaMinor").asLong()).isEqualTo(149_500);
      assertThat(reviewed.at("/totalEffect/outTheDoorDeltaMinor").asLong())
          .isEqualTo(review.at("/cashDeltas/baseline/outTheDoorDeltaMinor").asLong());

      // The comparison is a deterministic read; it stays inside the recalculation latency target.
      List<Long> timings = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        long start = System.nanoTime();
        get(client, desk, token);
        timings.add((System.nanoTime() - start) / 1_000_000);
      }
      Collections.sort(timings);
      assertThat(timings.get(18)).as("desk check p95 ms").isLessThan(500);

      // Scenario 24: a different car's four-square is blocked, never merged, and starts its own case.
      paperwork = "OTHER_CAR";
      String otherDocument = upload(app, base, token, "desk-2");
      var other = get(client, desk, token);
      assertThat(other.path("status").asText()).isEqualTo("DIFFERENT_VIN");
      assertThat(other.at("/findings/0/group").asText()).isEqualTo("VEHICLE");
      assertThat(other.at("/findings/0/severity").asText()).isEqualTo("BLOCKING");
      assertThat(other.path("findings")).hasSize(1);
      assertThat(other.at("/freshCase/vin").asText()).isEqualTo(OTHER_VIN);
      assertThat(other.at("/freshCase/dealerLocation").asText()).isEqualTo("Raleigh, NC");
      assertThat(other.at("/freshCase/dealerPostalCode").isNull()).isTrue();
      assertThat(other.at("/freshCase/purchaseMethod").asText()).isEqualTo("CASH");
      int offersBefore = get(client, base + "/dealer-offers/latest", token).path("version").asInt();
      assertThatThrownBy(() -> verify(client, base, token, otherDocument, "other", 2_829_500))
          .isInstanceOf(HttpClientErrorException.class);
      assertThat(get(client, base + "/dealer-offers/latest", token).path("version").asInt()).isEqualTo(offersBefore);

      var fresh = post(client, "/api/v1/cases", validCase(), "fresh-case");
      String freshId = fresh.at("/case/id").asText(), freshToken = fresh.path("accessToken").asText();
      String freshBase = "/api/v1/cases/" + freshId;
      post(client, freshBase + "/vehicle-evidence", Map.of("vin", OTHER_VIN, "confirmed", true), "fresh-vehicle", freshToken);
      String freshDocument = upload(app, freshBase, freshToken, "fresh-paperwork");
      verify(client, freshBase, freshToken, freshDocument, "fresh", 2_829_500);
      post(client, freshBase + "/cash-economics", Map.of(), "fresh-cash", freshToken);
      post(client, freshBase + "/market-assessments", Map.of(), "fresh-market", freshToken);
      post(client, freshBase + "/analyses", Map.of(), "fresh-analysis", freshToken);
      var freshReport = get(client, freshBase + "/reports/latest", freshToken);
      assertThat(freshReport.at("/evidence/vehicle/vehicle/vin").asText()).isEqualTo(OTHER_VIN);
      assertThat(freshReport.at("/evidence/buyerJurisdiction").asText()).isEqualTo("US-NC");
      assertThat(get(client, freshBase + "/desk-check", freshToken).path("status").asText()).isEqualTo("NO_AGREED_PRICE");
      assertThatThrownBy(() -> get(client, freshBase + "/desk-check", token)).isInstanceOf(HttpClientErrorException.class);
      assertThat(get(client, base + "/quote-acceptance", token).path("agreedPrice")).isEqualTo(agreedPrice);
    }
  }

  static String upload(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = new ObjectMapper().readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    assertThat(awaitExtraction(app, started.at("/operation/id").asText(), token).path("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
    return started.at("/document/id").asText();
  }

  static JsonNode verify(RestClient client, String base, String token, String documentId, String key, long financed) throws Exception {
    String document = base + "/documents/" + documentId;
    var extraction = get(client, document + "/extractions/latest", token);
    String corrections = document + "/extractions/" + extraction.path("id").asText() + "/corrections";
    post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
        "normalizedValue", Long.toString(financed), "normalizedUnit", "USD_MINOR"), key + "-financed", token);
    post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), key + "-fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    return post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), key + "-verified", token);
  }
}
