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

/**
 * Ticket 139: a padded "tag, title and registration" line on North Carolina paperwork is set against the state's
 * fee schedule in the Desk Check, the "I'm at the dealer" result and the Deal Review. The paperwork is the
 * labelled synthetic fixture quote with one state fee line added.
 */
class GovernmentFeeAcceptanceTest {
  /** The amount of the paperwork's "Tag, title and registration" line, in cents; 0 leaves the line out. */
  static volatile long stateFees = 0;

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider governmentFeeProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        var second = new EvidenceRegion(2, .08, .16, .55, .04);
        // An itemized quote states its add-ons, even when there are none, as in the "I'm at the dealer" fixture.
        var candidates = new ArrayList<>(extracted.candidates());
        candidates.add(new Candidate("ADD_ON_TOTAL", "Add-ons $0", "0", "USD_MINOR", second, .99, true));
        var lines = new ArrayList<>(extracted.lineItems());
        if (stateFees > 0) lines.add(new LineItem("Tag, title and registration", "GOVERNMENT_CHARGE", stateFees, "USD", "NONTAXABLE",
            "REQUIRED", new EvidenceRegion(2, .08, .20, .55, .04), .97));
        return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
            extracted.expectedPageCount(), candidates, lines, extracted.documentWarnings());
      };
    }
  }

  @TempDir Path directory;

  @Test void aPaddedStateFeeLineIsAskedAboutInTheDeskCheckAtTheDealerAndInTheDealReview() throws Exception {
    stateFees = 0;
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("governmentfees", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      // A North Carolina cash purchase (postal code 27601).
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);

      // The agreed quote lists no state fee, so its report has no fee check and keeps its shape.
      var agreed = post(client, base + "/analyses", Map.of(), "report-agreed", token);
      awaitCompleted(client, agreed.at("/operation/id").asText(), token);
      assertThat(get(client, base + "/reports/latest", token).path("analysis").has("governmentFeeCheck")).isFalse();

      // Desk paperwork pads the line to $895, more than North Carolina's listed fees for it can add up to.
      stateFees = 89_500;
      String desk = upload(app, base, token, "desk");
      var deskCheck = get(client, base + "/desk-check", token);
      assertThat(deskCheck.path("source").asText()).isEqualTo("READ_FROM_PAPERWORK");
      var unverified = finding(deskCheck.path("governmentFees"), "ABOVE_SCHEDULE");
      assertThat(unverified.path("label").asText()).isEqualTo("Tag, title and registration");
      assertThat(unverified.path("amountMinor").asLong()).isEqualTo(89_500);
      // A cash purchase records no lien: $66.75 + $126 one-day title + $122 + $214.50 + $47 + $25.50.
      assertThat(unverified.path("highestMinor").asLong()).isEqualTo(60_175);
      assertThat(unverified.path("verified").asBoolean()).isFalse();
      assertThat(unverified.path("summary").asText()).contains("“Tag, title and registration” on this paperwork is $895.",
          "North Carolina's listed title, registration and plate fees add up to at most $601.75");
      assertThat(unverified.path("deskQuestion").asText()).isEqualTo("Please itemize the state fees on this line.");
      assertThat(unverified.at("/evidence/pageNumber").asInt()).isEqualTo(2);

      var first = TradeVehicleAcceptanceTest.call(client, "POST", base + "/desk-start/result",
          Map.of("documentId", desk, "route", "AGREED_CAR"), token, null).body();
      var atTheDealer = find(first.path("findings"), "code", "GOVERNMENT_FEE_ABOVE_SCHEDULE");
      assertThat(atTheDealer.path("summary").asText()).isEqualTo(unverified.path("summary").asText());
      assertThat(atTheDealer.path("deskQuestion").asText()).isEqualTo("Please itemize the state fees on this line.");
      assertThat(first.at("/governmentFees/status").asText()).isEqualTo("CHECKED");

      // Verified as a revised offer, the padded line is a verified finding in the Deal Review and the Desk Check.
      var extraction = get(client, base + "/documents/" + desk + "/extractions/latest", token);
      String corrections = base + "/documents/" + desk + "/extractions/" + extraction.path("id").asText() + "/corrections";
      post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field",
          "AMOUNT_FINANCED").path("id").asText(), "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "desk-finance", token);
      post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category",
          "UNKNOWN").path("id").asText(), "category", "DEALER_FEE", "amountMinor", 89900), "desk-fee", token);
      var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
      extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
      extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
      var revised = post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
          "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "desk-offer", token);
      assertThat(revised.path("version").asInt()).isGreaterThan(1);
      var analysis = post(client, base + "/analyses", Map.of(), "report-padded", token);
      awaitCompleted(client, analysis.at("/operation/id").asText(), token);
      var report = get(client, base + "/reports/latest", token).path("analysis");
      assertThat(report.path("dealReview").isObject()).isTrue();
      var reviewed = finding(report.path("governmentFeeCheck"), "ABOVE_SCHEDULE");
      assertThat(reviewed.path("verified").asBoolean()).isTrue();
      assertThat(reviewed.path("highestMinor").asLong()).isEqualTo(60_175);
      assertThat(report.at("/governmentFeeCheck/summary").asText()).isEqualTo(reviewed.path("summary").asText());
      assertThat(report.at("/governmentFeeCheck/state").asText()).isEqualTo("NC");
      assertThat(report.path("governmentFeeCheck").path("fees").findValuesAsText("name"))
          .contains("Certificate of title", "County vehicle property tax");

      var verifiedCheck = get(client, base + "/desk-check", token);
      assertThat(verifiedCheck.path("source").asText()).isIn("VERIFIED_OFFER", "DEAL_REVIEW");
      assertThat(finding(verifiedCheck.path("governmentFees"), "ABOVE_SCHEDULE").path("verified").asBoolean()).isTrue();
    } finally {
      stateFees = 0;
    }
  }

  @Test void aStateWithNoScheduleSaysTheFeesWereNotChecked() throws Exception {
    stateFees = 0;
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("governmentfeesunchecked", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      // A jurisdiction the schedule does not cover, as a case from before ticket 139's states might hold.
      app.context().getBean(org.springframework.jdbc.core.JdbcTemplate.class)
          .update("update analysis_cases set buyer_jurisdiction = 'SYNTHETIC-FIXTURE' where id = ?", caseId);
      stateFees = 89_500;
      upload(app, base, token, "desk");
      var fees = get(client, base + "/desk-check", token).path("governmentFees");
      assertThat(fees.path("status").asText()).isEqualTo("NOT_CHECKED");
      assertThat(fees.path("findings")).isEmpty();
      assertThat(fees.path("summary").asText()).isEqualTo(
          "otdog has no fee schedule for your state, so the $895 in state fees on this paperwork was not checked.");
    } finally {
      stateFees = 0;
    }
  }

  private static JsonNode finding(JsonNode check, String code) {
    assertThat(check.isObject()).as("the fee check is present").isTrue();
    return find(check.path("findings"), "code", code);
  }

  private static String upload(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = new ObjectMapper().readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    awaitExtraction(app, started.at("/operation/id").asText(), token);
    return started.at("/document/id").asText();
  }
}
