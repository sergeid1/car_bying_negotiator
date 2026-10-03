package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.report.StoredReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/** Text-bearing synthetic PDF development check; no real-document accuracy claim. */
class FourSquareWorksheetAcceptanceTest {
  @TempDir Path temporaryDirectory;

  @Test void paymentOnlyWorksheetReachesSavedReportWithoutInventingTotalOrTerm() throws Exception {
    try (var app = new HttpAcceptanceHarness(temporaryDirectory).start("four-square", true,
        "--document.storage.path=" + temporaryDirectory.resolve("four-square-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var created = HttpAcceptanceHarness.post(app.client(), "/api/v1/cases", Map.of(
          "buyerPostalCode", "27601", "dealerPostalCode", "27604", "purchaseMethod", "FINANCE",
          "consents", Map.of("serviceProcessing", Map.of("granted", true, "policyVersion", "service.v1"),
              "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
              "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1"))), "create-four-square");
      String token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + created.at("/case/id").asText();
      var parts = new LinkedMultiValueMap<String, Object>();
      parts.add("file", new ByteArrayResource(worksheetPdf()) {
        @Override public String getFilename() { return "synthetic-four-square.pdf"; }
      });
      var json = new ObjectMapper();
      var upload = json.readTree(app.client().post().uri(path + "/documents")
          .contentType(MediaType.MULTIPART_FORM_DATA).header("Idempotency-Key", "upload-four-square")
          .header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
      while (true) {
        var operation = HttpAcceptanceHarness.get(app.client(), "/api/v1/operations/" + upload.at("/operation/id").asText(), token);
        if ("EXTRACTION_COMPLETE".equals(operation.path("stage").asText())) break;
        assertThat(operation.path("stage").asText()).isNotEqualTo("FAILED");
        assertThat(System.nanoTime()).isLessThan(deadline);
        Thread.sleep(25);
      }
      var extraction = HttpAcceptanceHarness.get(app.client(), path + "/documents/" + upload.at("/document/id").asText() + "/extractions/latest", token);
      assertThat(extraction.path("candidates")).hasSize(2);
      assertThat(extraction.path("candidates")).anySatisfy(c -> {
        assertThat(c.path("field").asText()).isEqualTo("DOCUMENT_TYPE");
        assertThat(c.path("normalizedValue").asText()).isEqualTo("FOUR_SQUARE_WORKSHEET");
      });
      HttpAcceptanceHarness.post(app.client(), path + "/analyses", Map.of(), "analyze-four-square", token);
      var report = HttpAcceptanceHarness.get(app.client(), path + "/reports/latest", token);
      assertThat(report.path("status").asText()).isEqualTo("PARTIAL");
      assertThat(report.at("/analysis/topReasons/0").asText()).contains("out-the-door total is missing", "hide the total");
      assertThat(report.at("/analysis/topReasons/1").asText()).contains("loan term is missing", "do not infer");
      assertThat(report.at("/analysis/financingTerms").isNull()).isTrue();
      assertThat(report.at("/evidence/fourSquareWorksheet/id").asText()).isEqualTo(extraction.path("id").asText());
      var renderer = app.context().getBean(StoredReportRenderer.class);
      assertThat(new String(renderer.html(report), StandardCharsets.UTF_8)).contains("out-the-door total is missing", "loan term is missing");
      try (var pdf = Loader.loadPDF(renderer.pdf(report))) {
        assertThat(new PDFTextStripper().getText(pdf)).contains("out-the-door total is missing", "loan term is missing");
      }
      assertThat(HttpAcceptanceHarness.get(app.client(), path + "/reports/1", token)).isEqualTo(report);
    }
  }

  private byte[] worksheetPdf() throws Exception {
    try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
      var page = new PDPage(); document.addPage(page);
      try (var text = new PDPageContentStream(document, page)) {
        text.beginText(); text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        text.newLineAtOffset(54, 730); text.showText("FOUR-SQUARE WORKSHEET");
        text.newLineAtOffset(260, -200); text.showText("Monthly Payment: $399.00"); text.endText();
      }
      document.save(output); return output.toByteArray();
    }
  }
}
