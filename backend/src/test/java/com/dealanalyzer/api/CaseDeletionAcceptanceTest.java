package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseDeletionAcceptanceTest {
  @TempDir Path directory;

  @Test void deletionImmediatelyRevokesAccessAndCanBeRetried() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("deletion", false,
        "--case.deletion.delay=PT1H", "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      post(app.client(), base + "/analyses", Map.of(), "report", token);
      var export = post(app.client(), base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      var receipt = post(app.client(), base + "/deletion", Map.of(), "delete", token);
      assertThat(receipt.path("state").asText()).isEqualTo("DELETION_PENDING");
      assertThatThrownBy(() -> get(app.client(), base, token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThatThrownBy(() -> get(app.client(), base + "/exports/" + export.path("id").asText(), token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      assertThat(post(app.client(), base + "/deletion", Map.of(), "retry", token).path("id"))
          .isEqualTo(receipt.path("id"));
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var completed = get(app.client(), base + "/deletion", token);
      assertThat(completed.path("state").asText()).isEqualTo("DELETED");
      assertThat(completed.path("verification").asText()).isEqualTo("CASE_GRAPH_AND_ARTIFACTS_ABSENT_V1");
      assertThat(completed.path("backupStatus").asText()).isEqualTo("UNKNOWN");
      assertThat(completed.path("backupExpiresAt").isNull()).isTrue();
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(get(app.client(), base + "/deletion", token)).isEqualTo(completed);
    }
  }
  @Test void partialStorageFailureIsRecordedAndRetrySurvivesRestart() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    Path objects = directory.resolve("objects");
    java.nio.file.Files.createDirectories(objects);
    String id, token;
    try (var app = harness.start("partial", false, "--case.deletion.delay=PT1H", "--document.storage.path=" + objects)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      id = created.at("/case/id").asText(); token = created.path("accessToken").asText();
      java.nio.file.Files.writeString(objects.resolve(id + "-a.enc"), "encrypted fixture");
      var blocked = java.nio.file.Files.createDirectory(objects.resolve(id + "-z.enc"));
      java.nio.file.Files.writeString(blocked.resolve("blocked"), "fixture");
      post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var pending = get(app.client(), "/api/v1/cases/" + id + "/deletion", token);
      assertThat(pending.path("state").asText()).isEqualTo("DELETION_PENDING");
      assertThat(pending.path("attempts").asInt()).isEqualTo(1);
      assertThat(pending.path("failureCode").asText()).isEqualTo("REMOVAL_NOT_VERIFIED");
      var metrics = app.context().getBean(com.dealanalyzer.buyingcase.persistence.DeletionMeasurements.class)
          .snapshot(java.time.Instant.now().plus(java.time.Duration.ofHours(25)));
      assertThat(metrics.overduePendingCases()).isEqualTo(1);
      assertThat(metrics.completedP95Millis()).isNull();
      assertThat(java.nio.file.Files.exists(objects.resolve(id + "-a.enc"))).isFalse();
      java.nio.file.Files.delete(blocked.resolve("blocked"));
    }
    try (var app = harness.start("partial", false, "--case.deletion.delay=PT1H", "--document.storage.path=" + objects)) {
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var done = get(app.client(), "/api/v1/cases/" + id + "/deletion", token);
      assertThat(done.path("state").asText()).isEqualTo("DELETED");
      assertThat(done.path("attempts").asInt()).isEqualTo(2);
      assertThat(done.at("/history/0/outcome").asText()).isEqualTo("FAILED");
      assertThat(done.at("/history/0/failureCode").asText()).isEqualTo("REMOVAL_NOT_VERIFIED");
      assertThat(done.at("/history/1/outcome").asText()).isEqualTo("VERIFIED");
      try (var files = java.nio.file.Files.list(objects)) { assertThat(files.toList()).isEmpty(); }
    }
  }

  @Test void buyerCanChooseShorterRetentionButCannotExtendTheDeadline() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("retention-choice", false, "--case.deletion.delay=PT1H")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + id + "/retention";
      var choice = post(app.client(), path, Map.of("days", 7), "shorter", token);
      assertThat(choice.path("maximumDocumentRetentionDays").asInt()).isEqualTo(30);
      assertThat(java.time.Instant.parse(choice.path("caseExpiresAt").asText()))
          .isEqualTo(java.time.Instant.parse(created.at("/case/createdAt").asText()).plus(java.time.Duration.ofDays(7)));
      assertThat(post(app.client(), path, Map.of("days", 30), "longer", token).path("caseExpiresAt"))
          .isEqualTo(choice.path("caseExpiresAt"));
      assertThatThrownBy(() -> post(app.client(), path, Map.of("days", 31), "invalid", token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.BadRequest.class);
    }
  }

  static void verifyCompleteRemoval(HttpAcceptanceHarness.RunningApplication app, String id, String token, Path objects) throws Exception {
    post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "delete-complete-fixture", token);
    app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
    assertThat(get(app.client(), "/api/v1/cases/" + id + "/deletion", token).path("state").asText()).isEqualTo("DELETED");
    // Physical removal is part of this fixture's contract: access denial alone cannot prove it.
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
    var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'", String.class);
    // Pilot protocol, anonymous totals and operator-supplied aggregate evidence have no case references.
    for (String table : tables) {
      if (java.util.Set.of("flyway_schema_history", "jurisdiction_policies", "case_deletion_jobs", "case_deletion_attempts",
          "pilot_protocol", "pilot_cohort_totals", "pilot_observations").contains(table)) continue;
      assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
    try (var files = java.nio.file.Files.list(objects)) { assertThat(files.toList()).isEmpty(); }
    System.out.println("Deletion fixture: " + jdbc.queryForMap(
        "select removed_rows, graph_duration_ms, attempts from case_deletion_jobs where case_id = ?", id));
  }

  @Test void deletionInvalidatesPreviouslyIssuedRecoveryLinks() throws Exception {
    CasePortabilityAcceptanceTest.links.clear();
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("revoke-recovery", false,
        CasePortabilityAcceptanceTest.EmailConfiguration.class, "--case.deletion.delay=PT1H")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      post(app.client(), "/api/v1/cases/" + id + "/recovery-email", Map.of("email", "buyer@example.com"), "enroll", token);
      String secret = CasePortabilityAcceptanceTest.links.getLast().split("#recover=")[1];
      post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      assertThatThrownBy(() -> post(app.client(), "/api/v1/recovery/verify", Map.of("token", secret)))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      post(app.client(), "/api/v1/recovery/requests", Map.of("caseId", id, "email", "buyer@example.com"));
      assertThat(CasePortabilityAcceptanceTest.links).hasSize(1);
    }
  }

  @Test void expiryRemovesTheCaseGraphWithoutABuyerRequest() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("expired-case", false,
        "--case.access.lifetime=PT0.2S", "--case.deletion.delay=PT1H")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      Thread.sleep(250);
      assertThatThrownBy(() -> get(app.client(), "/api/v1/cases/" + id, token))
          .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
      var worker = app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class);
      worker.runOnce(); worker.runOnce();
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select count(*) from analysis_cases", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from consent_records", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select state from case_deletion_jobs where case_id = ?", String.class, id)).isEqualTo("DELETED");
      assertThat(jdbc.queryForObject("select attempts from case_deletion_jobs where case_id = ?", Integer.class, id)).isEqualTo(1);
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class ProviderConfiguration {
    static final java.util.concurrent.atomic.AtomicBoolean copyExists = new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.context.annotation.Bean
    com.dealanalyzer.lifecycle.CaseArtifactErasure retainedProviderCopy() {
      return id -> { if (copyExists.get()) throw new IllegalStateException("Provider removal not yet verified"); };
    }
  }

  @Test void supportedProviderRemovalMustBeVerifiedBeforeCompletion() throws Exception {
    ProviderConfiguration.copyExists.set(true);
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("provider-copy", false,
        ProviderConfiguration.class, "--case.deletion.delay=PT1H")) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + id + "/deletion";
      post(app.client(), path, Map.of(), "delete", token);
      var worker = app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class);
      worker.runOnce();
      assertThat(get(app.client(), path, token).path("state").asText()).isEqualTo("DELETION_PENDING");
      ProviderConfiguration.copyExists.set(false);
      worker.runOnce();
      assertThat(get(app.client(), path, token).path("state").asText()).isEqualTo("DELETED");
    } finally { ProviderConfiguration.copyExists.set(false); }
  }

}
