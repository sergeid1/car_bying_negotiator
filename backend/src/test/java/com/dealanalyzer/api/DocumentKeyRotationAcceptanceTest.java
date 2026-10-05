package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.document.DocumentRetentionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;

class DocumentKeyRotationAcceptanceTest {
  @TempDir Path directory;
  private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
  private static String material() {
    byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
  private String[] config(String active, String keys) {
    return new String[]{"--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.active-key-id=" + active, "--document.storage.keys=" + keys,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--case.deletion.delay=PT1H"};
  }
  private String upload(RunningApplication app, String id, String token, String request) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new ByteArrayResource(PNG) { @Override public String getFilename() { return "document.png"; } });
    String response = app.client().post().uri("/api/v1/cases/" + id + "/documents")
        .header("Case-Access-Token", token).header("Idempotency-Key", request)
        .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(String.class);
    var upload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
    String document = upload.at("/document/id").asText();
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
    while (true) {
      var operation = get(app.client(), "/api/v1/operations/" + upload.at("/operation/id").asText(), token);
      String stage = operation.path("stage").asText();
      if (stage.equals("EXTRACTION_COMPLETE")) break;
      assertThat(stage).isNotEqualTo("FAILED");
      if (System.nanoTime() > deadline) throw new AssertionError("Document worker did not finish");
      Thread.sleep(250);
    }
    return document;
  }

  @Test void mixedGenerationsExportExpireAndDeleteAndMissingKeyReturnsSafeUnavailableResponse() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    String old = material(), current = material(), id, token, oldExpire, newExpire;
    try (var app = harness.start("rotation", true, config("old", "old=" + old))) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      id = created.at("/case/id").asText(); token = created.path("accessToken").asText();
      oldExpire = upload(app, id, token, "old-expire"); upload(app, id, token, "old-retain");
    }
    String base = "/api/v1/cases/" + id;
    try (var app = harness.start("rotation", false, config("new", "new=" + current))) {
      RestClientResponseException failure = catchThrowableOfType(() -> post(app.client(), base + "/exports",
          Map.of("format", "ZIP", "reportVersion", 0), "missing", token), RestClientResponseException.class);
      assertThat(failure.getStatusCode().value()).isEqualTo(503);
      assertThat(failure.getResponseBodyAsString()).contains("DOCUMENT_KEY_UNAVAILABLE", "retryable")
          .doesNotContain("local-kek", old, current);
    }
    try (var app = harness.start("rotation", true, config("new", "new=" + current + ",old=" + old))) {
      newExpire = upload(app, id, token, "new-expire"); upload(app, id, token, "new-retain");
      var job = post(app.client(), base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "mixed", token);
      byte[] zip = app.client().get().uri(base + "/exports/" + job.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      int documents = 0;
      try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
        for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
          if (entry.getName().startsWith("documents/")) {
            byte[] image = input.readAllBytes();
            if (!entry.getName().endsWith(".png")) assertThat(image).isEqualTo(PNG);
            else assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image))).isNotNull();
            documents++;
          }
        }
      }
      assertThat(documents).isEqualTo(8);
      try (var db = app.context().getBean(DataSource.class).getConnection(); var update = db.prepareStatement(
          "update source_documents set retention_deadline=? where id=?")) {
        for (String document : List.of(oldExpire, newExpire)) {
          update.setObject(1, java.time.OffsetDateTime.now().minusDays(1)); update.setString(2, document); update.executeUpdate();
        }
      }
      app.context().getBean(DocumentRetentionService.class).deleteExpiredArtifacts();
      try (var files = Files.list(directory.resolve("objects"))) { assertThat(files.count()).isEqualTo(4); }
      post(app.client(), base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(get(app.client(), base + "/deletion", token).path("state").asText()).isEqualTo("DELETED");
      try (var files = Files.list(directory.resolve("objects"))) { assertThat(files.count()).isZero(); }
    }
  }
}
