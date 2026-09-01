package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.dealanalyzer.document.DocumentPageNormalizer;

class DealerDocumentAcceptanceTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void buyerCanUploadSignedPngAndFollowSecureProcessing() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start(
        "document-upload",
        true,
        "--document.storage.path=" + temporaryDirectory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-document-case");
      String caseId = created.at("/case/id").asText();
      String accessToken = created.get("accessToken").asText();

      var parts = new LinkedMultiValueMap<String, Object>();
      parts.add("file", new NamedBytes("dealer-order.png", onePixelPng()));
      String response = application.client().post()
          .uri("/api/v1/cases/" + caseId + "/documents")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .header("Idempotency-Key", "upload-png")
          .header("Case-Access-Token", accessToken)
          .body(parts)
          .retrieve()
          .body(String.class);
      JsonNode upload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);

      assertThat(upload.at("/document/verifiedMediaType").asText()).isEqualTo("image/png");
      assertThat(upload.at("/document/originalFilename").asText()).isEqualTo("dealer-order.png");
      assertThat(upload.at("/document/checksumSha256").asText()).hasSize(64);
      assertThat(upload.at("/document/lifecycle").asText()).isEqualTo("ACTIVE");
      assertThat(upload.at("/document/retentionDeadline").asText()).isNotBlank();
      assertThat(upload.at("/document/encryption/keyReference").asText()).isNotBlank();
      assertThat(upload.at("/document/encryption/algorithm").asText()).isEqualTo("AES-256-GCM");
      assertThat(upload.at("/operation/stage").asText()).isNotBlank();
      assertThat(upload.at("/document/rawContent").isMissingNode()).isTrue();
      Path encrypted;
      try (var stored = Files.list(temporaryDirectory.resolve("objects"))) {
        encrypted = stored.findFirst().orElseThrow();
      }
      assertThat(java.util.Arrays.copyOf(Files.readAllBytes(encrypted), 8))
          .isNotEqualTo(java.util.Arrays.copyOf(onePixelPng(), 8));

      JsonNode completed = awaitTerminal(
          application, upload.at("/operation/id").asText(), accessToken);
      assertThat(completed.get("stage").asText()).isEqualTo("READY_FOR_EXTRACTION");
      assertThat(completed.get("documentId").asText())
          .isEqualTo(upload.at("/document/id").asText());
      JsonNode document = HttpAcceptanceHarness.get(
          application.client(),
          "/api/v1/cases/" + caseId + "/documents/" + upload.at("/document/id").asText(),
          accessToken);
      assertThat(document.at("/pages/0/pageNumber").asInt()).isEqualTo(1);
      assertThat(document.at("/pages/0/likelyDuplicate").asBoolean()).isFalse();
    }
  }

  @Test
  void signaturesDetermineSupportedMediaAndPdfPageWarningsPreserveOrder() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start(
        "supported-documents",
        true,
        "--document.storage.path=" + temporaryDirectory.resolve("supported-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-supported-documents");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      JsonNode png = upload(application, caseId, token, "signed-as-pdf.pdf", onePixelPng(), "png");
      JsonNode jpeg = upload(application, caseId, token, "photo.bin", onePixelJpeg(), "jpeg");
      JsonNode heic = upload(application, caseId, token, "phone-photo", minimalHeic(), "heic");
      JsonNode pdf = upload(application, caseId, token, "order.dat", warningPdf(), "pdf");

      assertThat(png.at("/document/verifiedMediaType").asText()).isEqualTo("image/png");
      assertThat(jpeg.at("/document/verifiedMediaType").asText()).isEqualTo("image/jpeg");
      assertThat(heic.at("/document/verifiedMediaType").asText()).isEqualTo("image/heic");
      assertThat(pdf.at("/document/verifiedMediaType").asText()).isEqualTo("application/pdf");
      awaitTerminal(application, pdf.at("/operation/id").asText(), token);
      JsonNode document = HttpAcceptanceHarness.get(
          application.client(),
          "/api/v1/cases/" + caseId + "/documents/" + pdf.at("/document/id").asText(),
          token);
      assertThat(document.get("pageCount").asInt()).isEqualTo(2);
      assertThat(document.at("/pages/0/pageNumber").asInt()).isEqualTo(1);
      assertThat(document.at("/pages/1/pageNumber").asInt()).isEqualTo(2);
      assertThat(document.at("/pages/0/likelyMissing").asBoolean()).isTrue();
      assertThat(document.at("/pages/1/likelyDuplicate").asBoolean()).isTrue();
    }
  }

  @Test
  void unsafeAndUnsupportedFilesFailClosedWithActionableCodes() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start(
        "rejected-documents",
        true,
        "--document.storage.path=" + temporaryDirectory.resolve("rejected-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-rejected-documents");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      assertRejected(application, caseId, token, "program.png", new byte[] {'M', 'Z', 0, 0},
          "reject-executable", "EXECUTABLE_FILE");
      assertRejected(application, caseId, token, "locked.pdf",
          "%PDF-1.7\n1 0 obj<</Type /Page /Encrypt 2 0 R>>endobj\n%%EOF"
              .getBytes(StandardCharsets.ISO_8859_1),
          "reject-encrypted", "ENCRYPTED_FILE");
      assertRejected(application, caseId, token, "broken.png",
          new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
          "reject-malformed", "MALFORMED_FILE");
      assertRejected(application, caseId, token, "infected.png",
          infectedPng(),
          "reject-malware", "MALWARE_DETECTED");
      assertRejected(application, caseId, token, "notes.txt",
          "not a dealer document".getBytes(StandardCharsets.UTF_8),
          "reject-unsupported", "UNSUPPORTED_FILE");

      JsonNode reopened = HttpAcceptanceHarness.get(
          application.client(), "/api/v1/cases/" + caseId, token);
      assertThat(reopened.get("status").asText()).isEqualTo("DRAFT");
      assertThat(Files.exists(temporaryDirectory.resolve("rejected-objects"))).isFalse();
    }
  }

  @Test
  void configuredSizePagePixelResolutionAndExpansionLimitsFailClosed() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    try (RunningApplication application = harness.start(
        "document-limits",
        true,
        "--document.storage.path=" + temporaryDirectory.resolve("limited-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--document.limits.max-file-bytes=128",
        "--document.limits.max-pages=1",
        "--document.limits.max-pixels=100",
        "--document.limits.min-width=2",
        "--document.limits.min-height=2",
        "--document.limits.max-expansion-ratio=1")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-document-limits");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      assertRejected(application, caseId, token, "large.pdf", new byte[129],
          "limit-size", "FILE_TOO_LARGE");
      assertRejected(application, caseId, token, "many-pages.pdf",
          "%PDF-1.4\n/Type /Page\n/Type /Page\n%%EOF".getBytes(StandardCharsets.ISO_8859_1),
          "limit-pages", "PAGE_LIMIT_EXCEEDED");
      assertRejected(application, caseId, token, "pixels.png", pngWithDimensions(11, 10),
          "limit-pixels", "PIXEL_LIMIT_EXCEEDED");
      assertRejected(application, caseId, token, "resolution.png", onePixelPng(),
          "limit-resolution", "RESOLUTION_TOO_LOW");
      assertRejected(application, caseId, token, "expansion.png", pngWithDimensions(20, 2),
          "limit-expansion", "DECOMPRESSION_RISK");
    }
  }

  @Test
  void uploadIsIdempotentWithoutDuplicatingEncryptedArtifact() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("idempotent-objects");
    try (RunningApplication application = harness.start(
        "idempotent-document",
        false,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-idempotent-document");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      JsonNode first = upload(application, caseId, token, "quote.png", onePixelPng(), "same-key");
      JsonNode replay = upload(application, caseId, token, "renamed.png", onePixelPng(), "same-key");

      assertThat(replay.at("/document/id").asText()).isEqualTo(first.at("/document/id").asText());
      assertThat(replay.at("/operation/id").asText()).isEqualTo(first.at("/operation/id").asText());
      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isEqualTo(1);
      }
    }
  }

  @Test
  void failedNormalizationCanRetryWithoutDuplicatingArtifact() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("retry-objects");
    try (RunningApplication application = harness.startWithConfiguration(
        "retry-document",
        true,
        FailFirstNormalization.class,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-retry-document");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();
      JsonNode upload = upload(
          application, caseId, token, "retry.png", onePixelPng(), "upload-retry-document");

      JsonNode failed = awaitTerminal(application, upload.at("/operation/id").asText(), token);
      assertThat(failed.get("stage").asText()).isEqualTo("FAILED");
      assertThat(failed.get("failedStage").asText()).isEqualTo("NORMALIZING");
      assertThat(failed.get("error").asText()).contains("Retry this stage");

      JsonNode retried = HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/operations/" + failed.get("id").asText() + "/retries",
          Map.of(),
          "retry-normalization",
          token);
      JsonNode completed = awaitTerminal(application, retried.get("id").asText(), token);
      assertThat(completed.get("stage").asText()).isEqualTo("READY_FOR_EXTRACTION");
      assertThat(completed.get("documentId").asText()).isEqualTo(failed.get("documentId").asText());
      assertThat(completed.get("retryCount").asInt()).isEqualTo(1);
      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isEqualTo(1);
      }
    }
  }

  private static JsonNode upload(
      RunningApplication application,
      String caseId,
      String token,
      String filename,
      byte[] bytes,
      String idempotencyKey) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(filename, bytes));
    String response = application.client().post()
        .uri("/api/v1/cases/" + caseId + "/documents")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", idempotencyKey)
        .header("Case-Access-Token", token)
        .body(parts)
        .retrieve()
        .body(String.class);
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
  }

  private static void assertRejected(
      RunningApplication application,
      String caseId,
      String token,
      String filename,
      byte[] bytes,
      String idempotencyKey,
      String code) throws Exception {
    RestClientResponseException error = org.assertj.core.api.Assertions.catchThrowableOfType(
        () -> upload(application, caseId, token, filename, bytes, idempotencyKey),
        RestClientResponseException.class);
    assertThat(error.getStatusCode().value()).isEqualTo(422);
    JsonNode problem = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(error.getResponseBodyAsString());
    assertThat(problem.get("code").asText()).isEqualTo(code);
    assertThat(problem.get("detail").asText()).containsAnyOf("retry", "try again");
    assertThat(problem.get("retryable").asBoolean()).isTrue();
  }

  private static JsonNode awaitTerminal(
      RunningApplication application, String operationId, String accessToken) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    JsonNode operation;
    do {
      operation = HttpAcceptanceHarness.get(
          application.client(), "/api/v1/operations/" + operationId, accessToken);
      if (operation.get("stage").asText().equals("READY_FOR_EXTRACTION")
          || operation.get("stage").asText().equals("FAILED")) {
        return operation;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Document operation did not finish: " + operation);
  }

  private static Map<String, Object> validCase() {
    return Map.of(
        "buyerJurisdiction", "US-NC",
        "dealerLocation", "27604",
        "purchaseMethod", "CASH",
        "consents", Map.of(
            "serviceProcessing", Map.of("granted", true, "policyVersion", "service.v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "research.v1")));
  }

  private static byte[] onePixelPng() {
    return java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
  }

  private static byte[] onePixelJpeg() {
    return java.util.Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/EB//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/EB//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/EB//2Q==");
  }

  private static byte[] pngWithDimensions(int width, int height) {
    byte[] png = onePixelPng();
    java.nio.ByteBuffer.wrap(png, 16, 8).putInt(width).putInt(height);
    return png;
  }

  private static byte[] infectedPng() {
    byte[] png = onePixelPng();
    byte[] marker = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);
    byte[] infected = java.util.Arrays.copyOf(png, png.length + marker.length);
    System.arraycopy(marker, 0, infected, png.length, marker.length);
    return infected;
  }

  private static byte[] minimalHeic() {
    byte[] bytes = new byte[1000];
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
    buffer.putInt(20).put("ftyp".getBytes(StandardCharsets.US_ASCII));
    buffer.put("heic".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("heic".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(20).put("ispe".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(0).putInt(1200).putInt(1600);
    return bytes;
  }

  private static byte[] warningPdf() {
    return ("%PDF-1.7\n"
        + "1 0 obj<</Type /Pages /Count 3>>endobj\n"
        + "2 0 obj<</Type /Page /Contents 9 0 R>>endobj\n"
        + "3 0 obj<</Type /Page /Contents 9 0 R>>endobj\n"
        + "%%EOF").getBytes(StandardCharsets.ISO_8859_1);
  }

  private static final class NamedBytes extends ByteArrayResource {
    private final String filename;

    private NamedBytes(String filename, byte[] bytes) {
      super(bytes);
      this.filename = filename;
    }

    @Override
    public String getFilename() {
      return filename;
    }
  }

  @TestConfiguration
  static class FailFirstNormalization {
    @Bean
    @Primary
    DocumentPageNormalizer failFirstPageNormalizer() {
      java.util.concurrent.atomic.AtomicBoolean first =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      return document -> {
        if (first.getAndSet(false)) {
          throw new IllegalStateException("Synthetic isolated-normalizer failure");
        }
      };
    }
  }
}
