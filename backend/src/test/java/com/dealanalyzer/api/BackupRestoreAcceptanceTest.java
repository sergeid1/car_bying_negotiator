package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupRestoreAcceptanceTest {
  @TempDir Path directory;

  @Test void restoreCannotStartWithoutAValidDeletionLedger() throws Exception {
    Path ledger = directory.resolve("invalid-ledger.txt");
    Files.writeString(ledger, "not-a-case-id\n");
    assertThatThrownBy(() -> new HttpAcceptanceHarness(directory).start("invalid-restore", false,
        "--recovery.restore-mode=true", "--recovery.deletion-ledger=" + ledger))
        .hasMessageContaining("Restore requires a readable UUID deletion ledger");
  }

  @Test void restoredBackupReappliesLaterDeletionBeforeReturningToService() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    String deletedId, deletedToken, retainedId, retainedToken;
    Path objects = directory.resolve("objects"), ledger = directory.resolve("deletions.txt");
    String orphanId = java.util.UUID.randomUUID().toString();
    String[] settings = {"--document.storage.path=" + objects, "--case.deletion.delay=PT1H"};
    try (var app = harness.start("original", false, settings)) {
      var deleted = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      var retained = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      deletedId = deleted.at("/case/id").asText(); deletedToken = deleted.path("accessToken").asText();
      retainedId = retained.at("/case/id").asText(); retainedToken = retained.path("accessToken").asText();
    }
    Files.copy(directory.resolve("original.mv.db"), directory.resolve("restored.mv.db"));
    try (var app = harness.start("original", false, settings)) {
      post(app.client(), "/api/v1/cases/" + deletedId + "/deletion", Map.of(), "delete", deletedToken);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(get(app.client(), "/api/v1/cases/" + deletedId + "/deletion", deletedToken).path("state").asText()).isEqualTo("DELETED");
    }
    // The deletion ledger is newer than the database backup and kept separately by the operator.
    Files.writeString(ledger, deletedId + "\n" + orphanId + "\n");
    Files.createDirectories(objects);
    Files.writeString(objects.resolve(deletedId + "-restored.enc"), "restored encrypted artifact fixture");
    Files.writeString(objects.resolve(orphanId + "-restored.enc"), "artifact restored without case metadata");
    try (var app = harness.start("restored", false,
        "--document.storage.path=" + objects, "--recovery.restore-mode=true",
        "--recovery.deletion-ledger=" + ledger, "--case.deletion.delay=PT1H")) {
      assertThatThrownBy(() -> get(app.client(), "/api/v1/cases/" + retainedId, retainedToken))
          .isInstanceOf(org.springframework.web.client.HttpServerErrorException.ServiceUnavailable.class);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
    }
    try (var app = harness.start("restored", false, settings)) {
      assertThatThrownBy(() -> get(app.client(), "/api/v1/cases/" + deletedId, deletedToken))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThat(get(app.client(), "/api/v1/cases/" + retainedId, retainedToken).path("id").asText()).isEqualTo(retainedId);
      assertThat(Files.exists(objects.resolve(deletedId + "-restored.enc"))).isFalse();
      assertThat(Files.exists(objects.resolve(orphanId + "-restored.enc"))).isFalse();
    }
  }
}
