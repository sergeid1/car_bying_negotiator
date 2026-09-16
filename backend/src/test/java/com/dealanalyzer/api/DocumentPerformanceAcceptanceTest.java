package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

class DocumentPerformanceAcceptanceTest {
  @TempDir Path directory;

  @Test void supportedTextDocumentsReachReviewReadyWithinPercentileTargets() throws Exception {
    long[] samples = new long[20];
    try (var app = new HttpAcceptanceHarness(directory).start("document-performance", true,
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      byte[] pdf = DocumentExtractionAcceptanceTest.supportedDealerQuotePdf();
      for (int i = 0; i < samples.length; i++) {
        var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
        String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(pdf) {
          @Override public String getFilename() { return "quote.pdf"; }
        });
        long start = System.nanoTime();
        var upload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(app.client().post()
            .uri("/api/v1/cases/" + id + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
            .header("Case-Access-Token", token).header("Idempotency-Key", "document-" + i)
            .body(parts).retrieve().body(String.class));
        while (true) {
          var op = get(app.client(), "/api/v1/operations/" + upload.at("/operation/id").asText(), token);
          assertThat(op.path("stage").asText()).isNotEqualTo("FAILED");
          if (op.path("stage").asText().equals("EXTRACTION_COMPLETE")) break;
          assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(180_000);
          Thread.sleep(50);
        }
        var extraction = get(app.client(), "/api/v1/cases/" + id + "/documents/"
            + upload.at("/document/id").asText() + "/extractions/latest", token);
        samples[i] = (System.nanoTime() - start) / 1_000_000;
        assertThat(extraction.path("candidates").size()).isPositive();
      }
    }
    Arrays.sort(samples);
    Files.createDirectories(Path.of("target/hardening"));
    Files.writeString(Path.of("target/hardening/documents.json"),
        "{\"scope\":\"20 sequential two-page PDFs, H2, test scanner, in-process PDF normalizer and deterministic extractor\","
        + "\"samples\":20,\"p50Ms\":" + samples[9] + ",\"p95Ms\":" + samples[18] + "}");
    assertThat(samples[9]).isLessThan(60_000);
    assertThat(samples[18]).isLessThan(180_000);
  }
}
