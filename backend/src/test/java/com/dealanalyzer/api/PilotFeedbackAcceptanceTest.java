package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpClientErrorException;

class PilotFeedbackAcceptanceTest {
  @TempDir Path directory;
  static Map<String, Object> buyingCase() {
    return Map.of("buyerJurisdiction", "US-NC", "dealerLocation", "27604", "purchaseMethod", "CASH",
        "consents", Map.of("serviceProcessing", Map.of("granted", true, "policyVersion", "v1"),
            "humanReview", Map.of("granted", false, "policyVersion", "v1"),
            "anonymizedResearch", Map.of("granted", false, "policyVersion", "v1")));
  }
  @Test void buyerRatesAnExistingFindingWithoutSharingTermsOrChangingTheReport() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("feedback", false, ServiceOperationsAcceptanceTest.SETTINGS)) {
      var created = post(app.client(), "/api/v1/cases", buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      post(app.client(), base + "/analyses", Map.of(), "analysis", token);
      var report = get(app.client(), base + "/reports/1", token);
      var input = Map.of("reportVersion", 1, "findingPointer", "/analysis/reviewStatus", "accurate", "YES", "understandable", "NO", "useful", "UNSURE");
      assertThatThrownBy(() -> post(app.client(), base + "/feedback/findings", input, "rating", "wrong-token"))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      var saved = post(app.client(), base + "/feedback/findings", input, "rating", token);
      assertThat(saved.path("accurate").asText()).isEqualTo("YES");
      assertThat(post(app.client(), base + "/feedback/findings", input, "rating", token)).isEqualTo(saved);
      assertThatThrownBy(() -> post(app.client(), base + "/feedback/findings", Map.of("reportVersion", 1,
          "findingPointer", "/evidence/dealerOffer", "accurate", "YES", "understandable", "YES", "useful", "YES"), "bad", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThat(get(app.client(), base + "/reports/1", token)).isEqualTo(report);
      assertThat(get(app.client(), base + "/feedback", token).path("outcomes")).isEmpty();
    }
  }
  @Test void outcomesAreOptionalSelfReportsAndDisappearWithCaseDeletion() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("outcome", false, ServiceOperationsAcceptanceTest.SETTINGS)) {
      var created = post(app.client(), "/api/v1/cases", buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      var input = Map.of("stage", "FINAL", "action", "PURCHASED", "outTheDoorMinor", 2850000,
          "currency", "USD", "understoodDecision", true, "useful", "YES", "trust", "YES",
          "liveUse", true, "disappointment", "VERY_DISAPPOINTED");
      var saved = post(app.client(), base + "/feedback/outcomes", input, "outcome", token);
      assertThat(saved.path("verification").asText()).isEqualTo("BUYER_REPORTED_UNVERIFIED");
      assertThat(post(app.client(), base + "/feedback/outcomes", input, "outcome", token)).isEqualTo(saved);
      assertThat(get(app.client(), base + "/feedback", token).path("outcomes")).hasSize(1);
      assertThat(get(app.client(), base + "/feedback", token).path("findings")).isEmpty();
      assertThatThrownBy(() -> post(app.client(), base + "/feedback/outcomes", Map.of("stage", "REVISED", "outTheDoorMinor", -1), "bad", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      post(app.client(), base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThatThrownBy(() -> get(app.client(), base + "/feedback", token)).isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThat(ServiceOperationsAcceptanceTest.opGet(app.client(), "/api/v1/operator/cases/" + id + "/deletion", ServiceOperationsAcceptanceTest.SUPPORT)
          .at("/job/verification").asText()).isEqualTo("CASE_GRAPH_AND_ARTIFACTS_ABSENT_V1");
    }
  }

  static Map<String, Object> protocol() {
    return Map.of("completionMinimum", 0.70, "usefulnessMinimum", 0.80, "veryDisappointedMinimum", 0.40,
        "numericErrorMaximum", 0.01, "criticalPrivacyMaximum", 0,
        "variants", Map.of("A", 1900, "B", 2900));
  }
  @Test void protocolFreezesBeforeReviewAndAssignmentCannotAffectAnalysis() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("pilot", false, ServiceOperationsAcceptanceTest.SETTINGS)) {
      String operator = "/api/v1/operator/pilot";
      String support = ServiceOperationsAcceptanceTest.SUPPORT;
      assertThatThrownBy(() -> ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/metrics", support))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      assertThatThrownBy(() -> ServiceOperationsAcceptanceTest.opPost(app.client(), operator + "/freeze", protocol(), ServiceOperationsAcceptanceTest.REVIEWER))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);
      var frozen = ServiceOperationsAcceptanceTest.opPost(app.client(), operator + "/freeze", protocol(), support);
      assertThat(frozen.path("frozenAt").asText()).isNotBlank();
      assertThatThrownBy(() -> ServiceOperationsAcceptanceTest.opPost(app.client(), operator + "/freeze", protocol(), support))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      var created = post(app.client(), "/api/v1/cases", buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText(), base = "/api/v1/cases/" + id;
      var assignment = ServiceOperationsAcceptanceTest.opPost(app.client(), operator + "/cases/" + id + "/enrollment", Map.of("qualified", true), support);
      assertThat(ServiceOperationsAcceptanceTest.opPost(app.client(), operator + "/cases/" + id + "/enrollment", Map.of("qualified", true), support)).isEqualTo(assignment);
      assertThat(assignment.path("variant").asText()).isIn("A", "B");
      post(app.client(), base + "/analyses", Map.of(), "analysis", token);
      var before = get(app.client(), base + "/reports/1", token).path("analysis").deepCopy();
      post(app.client(), base + "/feedback/outcomes", Map.of("stage", "FINAL", "useful", "YES", "liveUse", true,
          "disappointment", "VERY_DISAPPOINTED", "willingToPay", true, "understoodDecision", true), "outcome", token);
      post(app.client(), base + "/analyses", Map.of(), "regenerate", token);
      var after = get(app.client(), base + "/reports/2", token).path("analysis").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) before).remove("limitations");
      ((com.fasterxml.jackson.databind.node.ObjectNode) after).remove("limitations");
      assertThat(after).isEqualTo(before);
      var unassigned = post(app.client(), "/api/v1/cases", buyingCase());
      String controlBase = "/api/v1/cases/" + unassigned.at("/case/id").asText();
      String controlToken = unassigned.path("accessToken").asText();
      post(app.client(), controlBase + "/analyses", Map.of(), "unassigned-report", controlToken);
      var control = get(app.client(), controlBase + "/reports/1", controlToken).path("analysis").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) control).remove("limitations");
      assertThat(after).isEqualTo(control);
      ServiceOperationsAcceptanceTest.SqlCapture.statements.clear();
      var metrics = ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/metrics", support);
      assertThat(metrics.at("/qualifiedCompletion/denominator").asLong()).isEqualTo(1);
      assertThat(metrics.at("/qualifiedCompletion/numerator").asLong()).isZero(); // Partial reports do not pass completion.
      assertThat(metrics.at("/usefulness/numerator").asLong()).isEqualTo(1);
      assertThat(metrics.at("/veryDisappointed/denominator").asLong()).isEqualTo(1);
      assertThat(metrics.toString()).doesNotContain(id, token, "payloadJson", "outTheDoorMinor");
      assertThat(String.join(" ", ServiceOperationsAcceptanceTest.SqlCapture.statements)).doesNotContain("payload_json", "vehicle_json", "input_json", "normalized_value");
      assertThat(ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/research", support).path("respondents").asInt()).isZero();
      post(app.client(), base + "/consents", Map.of("purpose", "ANONYMIZED_RESEARCH", "granted", true, "policyVersion", "research.v1"), "grant", token);
      assertThat(ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/research", support).path("respondents").asInt()).isEqualTo(1);
      post(app.client(), base + "/consents", Map.of("purpose", "ANONYMIZED_RESEARCH", "granted", false, "policyVersion", "research.v1"), "revoke", token);
      assertThat(ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/research", support).path("respondents").asInt()).isZero();
      post(app.client(), base + "/deletion", Map.of(), "delete-pilot", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var deleted = ServiceOperationsAcceptanceTest.opGet(app.client(), operator + "/metrics", support);
      assertThat(deleted.at("/qualifiedCompletion/denominator").asLong()).isEqualTo(1);
      assertThat(deleted.at("/cohort/0/deleted").asLong()).isEqualTo(1);
      assertThat(deleted.at("/usefulness/denominator").asLong()).isZero();
    }
  }

  @Test void labeledMeasurementsAreUnavailableUntilRecordedAndRetriesDoNotDoubleCount() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("measurements", false, ServiceOperationsAcceptanceTest.SETTINGS)) {
      String base = "/api/v1/operator/pilot", support = ServiceOperationsAcceptanceTest.SUPPORT;
      ServiceOperationsAcceptanceTest.opPost(app.client(), base + "/freeze", protocol(), support);
      var empty = ServiceOperationsAcceptanceTest.opGet(app.client(), base + "/metrics", support);
      assertThat(empty.at("/verifiedMeasurements/NUMERIC_EXACT_MATCH/status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(empty.at("/qualifiedCompletion/rate").isNull()).isTrue();
      var input = Map.of("evidenceId", java.util.UUID.randomUUID().toString(), "metric", "NUMERIC_EXACT_MATCH",
          "numerator", 99, "denominator", 100, "source", "LABELED_EVALUATION");
      var first = ServiceOperationsAcceptanceTest.opPost(app.client(), base + "/observations", input, support);
      assertThat(ServiceOperationsAcceptanceTest.opPost(app.client(), base + "/observations", input, support)).isEqualTo(first);
      var metrics = ServiceOperationsAcceptanceTest.opGet(app.client(), base + "/metrics", support);
      assertThat(metrics.at("/verifiedMeasurements/NUMERIC_EXACT_MATCH/numerator").asInt()).isEqualTo(99);
      assertThat(metrics.at("/verifiedMeasurements/NUMERIC_EXACT_MATCH/denominator").asInt()).isEqualTo(100);
      assertThatThrownBy(() -> ServiceOperationsAcceptanceTest.opPost(app.client(), base + "/observations",
          Map.of("evidenceId", java.util.UUID.randomUUID().toString(), "metric", "NUMERIC_EXACT_MATCH", "numerator", 101, "denominator", 100, "source", "LABELED_EVALUATION"), support))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }
  }

  @Test void feedbackAssignmentsAndFrozenProtocolSurviveRestartAndExport() throws Exception {
    var harness = new HttpAcceptanceHarness(directory);
    String id, token;
    com.fasterxml.jackson.databind.JsonNode saved, assignment, frozen;
    String support = ServiceOperationsAcceptanceTest.SUPPORT;
    var settings = new java.util.ArrayList<>(java.util.List.of(ServiceOperationsAcceptanceTest.SETTINGS));
    settings.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    try (var app = harness.start("restart-feedback", false, settings.toArray(String[]::new))) {
      frozen = ServiceOperationsAcceptanceTest.opPost(app.client(), "/api/v1/operator/pilot/freeze", protocol(), support);
      var created = post(app.client(), "/api/v1/cases", buyingCase());
      id = created.at("/case/id").asText(); token = created.path("accessToken").asText();
      assignment = ServiceOperationsAcceptanceTest.opPost(app.client(), "/api/v1/operator/pilot/cases/" + id + "/enrollment", Map.of("qualified", true), support);
      post(app.client(), "/api/v1/cases/" + id + "/feedback/outcomes", Map.of("stage", "REVISED", "action", "NEGOTIATED", "outTheDoorMinor", 2800000, "currency", "USD"), "outcome", token);
      saved = get(app.client(), "/api/v1/cases/" + id + "/feedback", token);
    }
    try (var app = harness.start("restart-feedback", false, settings.toArray(String[]::new))) {
      String base = "/api/v1/cases/" + id;
      assertThat(get(app.client(), base + "/feedback", token)).isEqualTo(saved);
      assertThat(ServiceOperationsAcceptanceTest.opGet(app.client(), "/api/v1/operator/pilot/protocol", support)).isEqualTo(frozen);
      assertThat(ServiceOperationsAcceptanceTest.opPost(app.client(), "/api/v1/operator/pilot/cases/" + id + "/enrollment", Map.of("qualified", true), support)).isEqualTo(assignment);
      var export = post(app.client(), base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] bytes = app.client().get().uri(base + "/exports/" + export.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      boolean found = false;
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (!entry.getName().equals("case-data.json")) continue;
          var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(zip.readAllBytes());
          assertThat(data.path("outcome_feedback")).hasSize(1);
          assertThat(data.path("experiment_assignments")).hasSize(1);
          assertThat(data.toString()).doesNotContain(token); found = true;
        }
      }
      assertThat(found).isTrue();
    }
  }

}
