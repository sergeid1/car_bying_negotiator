package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.web.client.HttpClientErrorException;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import com.dealanalyzer.document.DocumentRetentionService;
import com.dealanalyzer.document.InvalidDocumentException;
import com.dealanalyzer.document.MalwareScanner;
import org.springframework.beans.factory.annotation.Qualifier;

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
      assertThat(completed.get("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
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
    Path heicDecoder = heicDecoderScript();
    try (RunningApplication application = harness.start(
        "supported-documents",
        true,
        "--document.storage.path=" + temporaryDirectory.resolve("supported-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--document.heic.decoder-command=" + heicDecoder)) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-supported-documents");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      JsonNode png = upload(application, caseId, token, "signed-as-pdf.pdf", onePixelPng(), "png");
      JsonNode jpeg = upload(application, caseId, token, "photo.bin", onePixelJpeg(), "jpeg");
      JsonNode heic = upload(application, caseId, token, "phone-photo", minimalHeic(), "heic");
      JsonNode pdf = upload(application, caseId, token, "order.dat", pdfWithPages(2), "pdf");

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
      assertThat(document.at("/pages/0/likelyMissing").asBoolean()).isFalse();
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
          encryptedPdf(),
          "reject-encrypted", "ENCRYPTED_FILE");
      assertRejected(application, caseId, token, "broken.png",
          new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
          "reject-malformed", "MALFORMED_FILE");
      assertRejected(application, caseId, token, "bad-crc.png",
          pngWithCorruptIhdrCrc(),
          "reject-bad-crc", "MALFORMED_FILE");
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
        "--document.limits.max-file-bytes=4096",
        "--document.limits.max-pages=1",
        "--document.limits.max-pixels=100",
        "--document.limits.min-width=2",
        "--document.limits.min-height=2",
        "--document.limits.max-expansion-ratio=1")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-document-limits");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();

      assertRejected(application, caseId, token, "large.pdf", new byte[4097],
          "limit-size", "FILE_TOO_LARGE");
      assertRejected(application, caseId, token, "many-pages.pdf",
          pdfWithPages(2),
          "limit-pages", "PAGE_LIMIT_EXCEEDED");
      assertRejected(application, caseId, token, "lying-page-tree.pdf",
          pdfWithDeclaredCount(1000),
          "limit-declared-pages", "PAGE_LIMIT_EXCEEDED");
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
  void scannerOutageFailsClosedBeforeAnythingIsStored() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("scanner-outage-objects");
    try (RunningApplication application = harness.startWithConfiguration(
        "scanner-outage", false, ScannerOutage.class,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-scanner-outage");

      assertRejected(
          application,
          created.at("/case/id").asText(),
          created.get("accessToken").asText(),
          "quote.png",
          onePixelPng(),
          "scanner-outage-upload",
          "MALWARE_SCAN_UNAVAILABLE");
      assertThat(Files.exists(objects)).isFalse();
    }
  }

  @Test
  void revokedServiceConsentPreventsReopenedUploadAndStorage() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("revoked-consent-objects");
    try (RunningApplication application = harness.start(
        "revoked-document-consent", false,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-revoked-document-consent");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();
      HttpAcceptanceHarness.get(application.client(), "/api/v1/cases/" + caseId, token);
      HttpAcceptanceHarness.post(
          application.client(),
          "/api/v1/cases/" + caseId + "/consents",
          Map.of(
              "purpose", "SERVICE_PROCESSING",
              "granted", false,
              "policyVersion", "service.v2"),
          "revoke-document-consent",
          token);

      assertRejected(
          application, caseId, token, "quote.png", onePixelPng(),
          "upload-after-revocation", "SERVICE_PROCESSING_CONSENT_REQUIRED");
      assertThat(Files.exists(objects)).isFalse();
    }
  }

  @Test
  void retentionWorkerDeletesOriginalAndNormalizedArtifactsAfterDeadline() throws Exception {
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("retention-objects");
    try (RunningApplication application = harness.start(
        "document-retention", true,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef",
        "--document.retention=PT10S",
        "--document.retention-cleanup-delay=PT1H")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-retention-case");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();
      JsonNode upload = upload(
          application, caseId, token, "quote.png", onePixelPng(), "retained-upload");
      awaitTerminal(application, upload.at("/operation/id").asText(), token);
      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isEqualTo(2);
      }

      Thread.sleep(10_250);
      application.context().getBean(DocumentRetentionService.class).deleteExpiredArtifacts();
      application.context().getBean(DocumentRetentionService.class).deleteExpiredArtifacts();

      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isZero();
      }
      assertThatThrownBy(() -> HttpAcceptanceHarness.get(application.client(),
          "/api/v1/cases/" + caseId + "/documents/" + upload.at("/document/id").asText(), token))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      application.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(application.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from source_documents where case_id = ?", Long.class, caseId)).isZero();
    }
  }

  @Test
  void unavailableStorageCannotVerifyDeletionAfterOnlyTheOriginalWasRemoved() throws Exception {
    Path objects = temporaryDirectory.resolve("partial-page-objects");
    Path unavailable = temporaryDirectory.resolve("unavailable-page-objects");
    try (RunningApplication application = new HttpAcceptanceHarness(temporaryDirectory).start(
        "partial-page", true, "--case.deletion.delay=PT1H", "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(application.client(), "/api/v1/cases", validCase());
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      JsonNode uploaded = upload(application, caseId, token, "quote.png", onePixelPng(), "partial-page-upload");
      awaitTerminal(application, uploaded.at("/operation/id").asText(), token);
      String documentId = uploaded.at("/document/id").asText();
      // Model the persisted state after original deletion succeeds but page deletion fails.
      Files.delete(objects.resolve(caseId + "-" + documentId + ".enc"));
      application.context().getBean(com.dealanalyzer.document.persistence.DocumentIntakePersistence.class)
          .markOriginalDeleted(documentId, java.time.Instant.now());
      Files.move(objects, unavailable);
      String deletionPath = "/api/v1/cases/" + caseId + "/deletion";
      HttpAcceptanceHarness.post(application.client(), deletionPath, java.util.Map.of(), "delete", token);
      var worker = application.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class);
      try {
        worker.runOnce();
        assertThat(HttpAcceptanceHarness.get(application.client(), deletionPath, token).path("state").asText())
            .isEqualTo("DELETION_PENDING");
      } finally { Files.move(unavailable, objects); }
      worker.runOnce();
      assertThat(HttpAcceptanceHarness.get(application.client(), deletionPath, token).path("state").asText())
          .isEqualTo("DELETED");
      try (var files = Files.list(objects)) { assertThat(files.toList()).isEmpty(); }
    }
  }

  @Test
  void concurrentRetryReplayDispatchesOnlyOneWorkerAndOneArtifactSet() throws Exception {
    BlockingRetryNormalization.reset();
    var harness = new HttpAcceptanceHarness(temporaryDirectory);
    Path objects = temporaryDirectory.resolve("concurrent-retry-objects");
    try (RunningApplication application = harness.startWithConfiguration(
        "concurrent-document-retry", true, BlockingRetryNormalization.class,
        "--document.storage.path=" + objects,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      JsonNode created = HttpAcceptanceHarness.post(
          application.client(), "/api/v1/cases", validCase(), "create-concurrent-retry");
      String caseId = created.at("/case/id").asText();
      String token = created.get("accessToken").asText();
      JsonNode upload = upload(
          application, caseId, token, "quote.png", onePixelPng(), "concurrent-upload");
      JsonNode failed = awaitTerminal(application, upload.at("/operation/id").asText(), token);

      HttpAcceptanceHarness.post(
          application.client(), "/api/v1/operations/" + failed.get("id").asText() + "/retries",
          Map.of(), "winning-retry", token);
      assertThat(BlockingRetryNormalization.entered.await(
          5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
      try {
        var calls = java.util.stream.IntStream.range(0, 8)
            .<java.util.concurrent.Callable<JsonNode>>mapToObj(index -> () ->
                HttpAcceptanceHarness.post(
                    application.client(),
                    "/api/v1/operations/" + failed.get("id").asText() + "/retries",
                    Map.of(), index % 2 == 0 ? "winning-retry" : "other-retry-" + index,
                    token))
            .toList();
        for (var result : executor.invokeAll(calls)) {
          assertThat(result.get().get("id").asText()).isEqualTo(failed.get("id").asText());
        }
      } finally {
        executor.shutdownNow();
      }
      BlockingRetryNormalization.release.countDown();

      JsonNode completed = awaitTerminal(application, failed.get("id").asText(), token);
      assertThat(completed.get("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
      assertThat(completed.get("retryCount").asInt()).isEqualTo(1);
      assertThat(BlockingRetryNormalization.invocations.get()).isEqualTo(2);
      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isEqualTo(2);
      }
    } finally {
      BlockingRetryNormalization.release.countDown();
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
      assertThat(completed.get("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
      assertThat(completed.get("documentId").asText()).isEqualTo(failed.get("documentId").asText());
      assertThat(completed.get("retryCount").asInt()).isEqualTo(1);
      try (var stored = Files.list(objects)) {
        assertThat(stored.count()).isEqualTo(2);
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
      if (operation.get("stage").asText().equals("EXTRACTION_COMPLETE")
          || operation.get("stage").asText().equals("FAILED")) {
        return operation;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Document operation did not finish: " + operation);
  }

  private static Map<String, Object> validCase() {
    return Map.of(
        "buyerPostalCode", "27601",
        "dealerPostalCode", "27604",
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

  private static byte[] onePixelJpeg() throws Exception {
    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "JPEG", output);
    return output.toByteArray();
  }

  private static byte[] pngWithDimensions(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "PNG", output);
    return output.toByteArray();
  }

  private static byte[] pngWithCorruptIhdrCrc() {
    byte[] png = onePixelPng();
    java.nio.ByteBuffer.wrap(png, 16, 8).putInt(2).putInt(2);
    return png;
  }

  private static byte[] infectedPng() {
    byte[] png = onePixelPng();
    byte[] marker = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);
    int iend = png.length - 12;
    byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(type);
    crc.update(marker);
    java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(marker.length + 12);
    chunk.putInt(marker.length).put(type).put(marker).putInt((int) crc.getValue());
    byte[] infected = new byte[png.length + chunk.array().length];
    System.arraycopy(png, 0, infected, 0, iend);
    System.arraycopy(chunk.array(), 0, infected, iend, chunk.array().length);
    System.arraycopy(png, iend, infected, iend + chunk.array().length, 12);
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

  private static byte[] pdfWithPages(int count) throws Exception {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (int page = 0; page < count; page++) {
        document.addPage(new PDPage());
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private static byte[] pdfWithDeclaredCount(int count) throws Exception {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.getDocumentCatalog().getPages().getCOSObject().setInt(COSName.COUNT, count);
      document.save(output);
      return output.toByteArray();
    }
  }

  private static byte[] encryptedPdf() throws Exception {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
          "owner-password", "buyer-password",
          new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
      policy.setEncryptionKeyLength(128);
      document.protect(policy);
      document.save(output);
      return output.toByteArray();
    }
  }

  private Path heicDecoderScript() throws Exception {
    Path decoded = temporaryDirectory.resolve("decoded-heic.png");
    Files.write(decoded, onePixelPng());
    Path script = temporaryDirectory.resolve("decode-heic.sh");
    Files.writeString(script, "#!/bin/sh\ncp \"" + decoded + "\" \"$2\"\n");
    script.toFile().setExecutable(true);
    return script;
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
    DocumentPageNormalizer failFirstPageNormalizer(
        @Qualifier("isolatedDocumentPageNormalizer") DocumentPageNormalizer delegate) {
      java.util.concurrent.atomic.AtomicBoolean first =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      return document -> {
        if (first.getAndSet(false)) {
          throw new IllegalStateException("Synthetic isolated-normalizer failure");
        }
        delegate.normalize(document);
      };
    }
  }

  @TestConfiguration
  static class ScannerOutage {
    @Bean
    @Primary
    MalwareScanner unavailableScanner() {
      return content -> {
        throw new InvalidDocumentException(
            "MALWARE_SCAN_UNAVAILABLE",
            "The malware scanner could not verify this file. No document was stored; try again later.");
      };
    }
  }

  @TestConfiguration
  static class BlockingRetryNormalization {
    static java.util.concurrent.CountDownLatch entered;
    static java.util.concurrent.CountDownLatch release;
    static java.util.concurrent.atomic.AtomicInteger invocations;

    static void reset() {
      entered = new java.util.concurrent.CountDownLatch(1);
      release = new java.util.concurrent.CountDownLatch(1);
      invocations = new java.util.concurrent.atomic.AtomicInteger();
    }

    @Bean
    @Primary
    DocumentPageNormalizer blockingRetryPageNormalizer(
        @Qualifier("isolatedDocumentPageNormalizer") DocumentPageNormalizer delegate) {
      return document -> {
        int invocation = invocations.incrementAndGet();
        if (invocation == 1) {
          throw new IllegalStateException("Synthetic first-attempt failure");
        }
        entered.countDown();
        try {
          if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent retries");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(exception);
        }
        delegate.normalize(document);
      };
    }
  }
}
