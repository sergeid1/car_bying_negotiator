package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import com.dealanalyzer.report.StoredReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/** Labelled synthetic development paperwork, never an extraction accuracy measurement. */
class DeskTacticAcceptanceTest {
  static volatile String worksheet = "AGREED";
  @TempDir Path directory;

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider tacticProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        if (worksheet.equals("AGREED")) {
          var agreed = new ArrayList<>(extracted.candidates());
          agreed.add(new Candidate("ADD_ON_TOTAL", "Add-ons $0", "0", "USD_MINOR", new EvidenceRegion(2, .1, .2, .3, .1), .99, true));
          return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
              extracted.expectedPageCount(), agreed, extracted.lineItems(), extracted.documentWarnings());
        }
        var candidates = new ArrayList<Candidate>();
        for (var term : extracted.candidates()) if (!List.of("APR", "TERM_MONTHS", "MONTHLY_PAYMENT", "AMOUNT_FINANCED", "DOCUMENT_TYPE").contains(term.field())) candidates.add(term);
        var evidence = new EvidenceRegion(2, .1, .2, .3, .1);
        candidates.add(new Candidate("DOCUMENT_TYPE", "Synthetic four-square worksheet", "FOUR_SQUARE_WORKSHEET", "ENUM", evidence, .99, false));
        candidates.add(new Candidate("AMOUNT_FINANCED", "Amount financed $30,000", "3000000", "USD_MINOR", evidence, .99, true));
        candidates.add(new Candidate("APR", "APR 0%", "0", "PERCENT", evidence, .99, true));
        candidates.add(new Candidate("TERM_MONTHS", "Term 60 months", "60", "MONTH", evidence, .99, true));
        candidates.add(new Candidate("MONTHLY_PAYMENT", "Payment", worksheet.equals("CLEAN") ? "50000" : "54000", "USD_MINOR", evidence, .99, true));
        return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
            extracted.expectedPageCount(), candidates, extracted.lineItems(), extracted.documentWarnings());
      };
    }
  }

  @Test void deskAndFreshCaseReportFreezeFortyDollarPaymentGapAndPendingQuestion() throws Exception {
    worksheet = "AGREED";
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("tactics", true, Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "agreed-case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId;
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      worksheet = "PACKED";
      upload(app, base, token, "synthetic-packed-desk");
      var desk = get(client, base + "/desk-check", token);
      var tactic = desk.at("/tacticFindings/0");
      assertThat(tactic.path("code").asText()).isEqualTo("PAYMENT_PACKING");
      assertThat(tactic.path("monthlyEffectMinor").asLong()).isEqualTo(4000);
      assertThat(tactic.path("totalEffectMinor").asLong()).isEqualTo(240000);
      assertThat(tactic.path("verified").asBoolean()).isFalse();
      assertThat(tactic.at("/evidenceRegions/0/region/pageNumber").asInt()).isEqualTo(2);
      assertThat(tactic.path("questionReview").asText()).isEqualTo("PENDING_OPERATOR_REVIEW");

      var fresh = post(client, "/api/v1/cases", validCase(), "fresh-case");
      String freshBase = "/api/v1/cases/" + fresh.at("/case/id").asText(), freshToken = fresh.path("accessToken").asText();
      upload(app, freshBase, freshToken, "synthetic-packed-fresh");
      post(client, freshBase + "/analyses", Map.of(), "packed-report", freshToken);
      var report = get(client, freshBase + "/reports/latest", freshToken);
      assertThat(report.at("/analysis/tacticFindings/0")).isEqualTo(tactic);
      assertThat(report.at("/analysis/policyVersions/deskTactics").asText()).isEqualTo("desk-tactics.v2");
      assertThat(report.at("/evidence/deskTacticInputs/candidates")).isNotEmpty();
      var renderer = app.context().getBean(StoredReportRenderer.class);
      assertThat(new String(renderer.html(report), StandardCharsets.UTF_8)).contains("Possible payment packing", "Monthly effect: $40.00", "Full-term effect: $2400.00", "PENDING_OPERATOR_REVIEW", "Please show how");
      assertThat(report.at("/evidence/deskTacticWorksheet/id").asText()).isNotBlank();
      try (var pdf = Loader.loadPDF(renderer.pdf(report))) {
        assertThat(new PDFTextStripper().getText(pdf)).contains("Possible payment packing", "$40.00", "PENDING_OPERATOR_REVIEW");
      }
      worksheet = "CLEAN";
      upload(app, freshBase, freshToken, "synthetic-clean-fresh");
      post(client, freshBase + "/analyses", Map.of(), "clean-report", freshToken);
      assertThat(get(client, freshBase + "/reports/latest", freshToken).at("/analysis/tacticFindings")).isEmpty();
      assertThat(get(client, freshBase + "/reports/1", freshToken)).isEqualTo(report);
    }
  }

  private String upload(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>(); parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = new ObjectMapper().readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    assertThat(awaitExtraction(app, started.at("/operation/id").asText(), token).path("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
    return started.at("/document/id").asText();
  }
}
