package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ServiceOperationsAcceptanceTest {
  static final String SUPPORT = "support-secret-with-at-least-thirty-two-bytes";
  static final String OTHER = "other-reviewer-secret-with-at-least-thirty-two-bytes";
  static final String REVIEWER = "reviewer-secret-with-at-least-thirty-two-bytes";
  static final String[] SETTINGS = {
      "--operations.accounts.support.token=" + SUPPORT,
      "--operations.accounts.support.role=SUPPORT",
      "--operations.accounts.reviewer.token=" + REVIEWER,
      "--operations.accounts.reviewer.role=REVIEWER",
      "--operations.accounts.other.token=" + OTHER,
      "--operations.accounts.other.role=REVIEWER",
      "--spring.jpa.properties.hibernate.session_factory.statement_inspector=com.dealanalyzer.api.ServiceOperationsAcceptanceTest$SqlCapture",
      "--case.deletion.delay=PT1H"};
  @TempDir Path directory;

  @Test void supportCanInspectOnlyRedactedDiagnosticsAndDeletionEvidence() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("operations", false, SETTINGS)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      post(app.client(), "/api/v1/cases/" + id + "/analyses", Map.of(), "sensitive-idempotency-key", token);
      String path = "/api/v1/operator/cases/" + id;
      assertThatThrownBy(() -> get(app.client(), path)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> opGet(app.client(), path, token)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      SqlCapture.statements.clear();
      var diagnostics = opGet(app.client(), path, SUPPORT);
      assertPrivateColumnsNotSelected();
      assertThat(diagnostics.path("operations")).hasSize(1);
      assertThat(diagnostics.at("/reports/0/policies/review").asText()).isEqualTo("core-review.v1");
      assertThat(diagnostics.at("/operations/0/stage").asText()).isEqualTo("COMPLETE");
      assertThat(diagnostics.at("/operations/0/correlationId").asText()).isNotBlank();
      assertThat(diagnostics.toString()).doesNotContain(token, "sensitive-idempotency-key", "buyerJurisdiction", "payloadJson", "vehicleJson");
      post(app.client(), "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      assertThat(opGet(app.client(), path + "/deletion", SUPPORT).at("/job/state").asText()).isEqualTo("DELETION_PENDING");
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      SqlCapture.statements.clear();
      var deleted = opGet(app.client(), path + "/deletion", SUPPORT);
      assertPrivateColumnsNotSelected();
      assertThat(deleted.at("/job/verification").asText()).isEqualTo("CASE_GRAPH_AND_ARTIFACTS_ABSENT_V1");
      assertThat(deleted.toString()).doesNotContain("receiptHash", token);
    }
  }
  @Test void providerFailuresAndCostsAreVisibleWithoutProviderPayloads() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("usage", false, ProviderFixture.class, SETTINGS)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String path = "/api/v1/cases/" + id;
      post(app.client(), path + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
          Map.of("year", 2021, "make", "Honda", "model", "Accord", "mileage", 38000)), "vehicle", token);
      ProviderFixture.mode = "schema";
      post(app.client(), path + "/market-assessments", Map.of(), "schema", token);
      ProviderFixture.mode = "throw";
      post(app.client(), path + "/market-assessments", Map.of(), "failure", token);
      SqlCapture.statements.clear();
      var usage = opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT);
      assertPrivateColumnsNotSelected();
      assertThat(usage).hasSize(2);
      var providers = opGet(app.client(), "/api/v1/operator/providers", SUPPORT);
      assertThat(providers.findValuesAsText("status")).containsExactlyInAnyOrder("CONTRACT_FAILURE", "PROVIDER_FAILURE");
      assertThat(providers.get(0).path("callsWithKnownCost").asInt()).isZero();
      assertThat(usage.findValuesAsText("status")).containsExactlyInAnyOrder("CONTRACT_FAILURE", "PROVIDER_FAILURE");
      assertThat(usage.findValuesAsText("operation")).containsOnly("MARKET_RETRIEVAL");
      assertThat(usage.get(0).path("units").asInt()).isEqualTo(1);
      assertThat(usage.get(0).has("costUsd")).isTrue();
      assertThat(usage.get(0).path("costUsd").isNull()).isTrue();
      assertThat(usage.toString()).doesNotContain("SENSITIVE_PROVIDER_PAYLOAD", token);
    }
  }
  @org.springframework.boot.test.context.TestConfiguration
  static class ProviderFixture {
    static volatile String mode;
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    com.dealanalyzer.market.MarketDataProvider provider() {
      return new com.dealanalyzer.market.MarketDataProvider() {
        public com.dealanalyzer.market.MarketEvidence assess(com.dealanalyzer.buyingcase.SyntheticAnalysisRequest.VehicleInput input) { throw new UnsupportedOperationException(); }
        public ProviderResult retrieve(MarketVehicle vehicle) {
          if ("throw".equals(mode)) throw new IllegalStateException("SENSITIVE_PROVIDER_PAYLOAD");
          return ProviderResult.unavailable("SENSITIVE_PROVIDER_PAYLOAD", new ProviderSnapshot(
              "SENSITIVE_PROVIDER_PAYLOAD", "SENSITIVE_PROVIDER_PAYLOAD", "wrong-schema", "SENSITIVE_PROVIDER_PAYLOAD", "secret-link", java.time.Instant.now()));
        }
      };
    }
  }

  @Test void reviewRequiresCurrentConsentAndOnlyGrantsOneFieldToTheAssignedReviewer() throws Exception {
    var settings = new java.util.ArrayList<>(java.util.List.of(SETTINGS));
    settings.add("--operations.review-duration=PT1S");
    settings.add("--document.storage.path=" + directory.resolve("objects"));
    settings.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    try (var app = new HttpAcceptanceHarness(directory).start("review", true, settings.toArray(String[]::new))) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();
      parts.add("file", new org.springframework.core.io.ByteArrayResource(quotePdf()) {
        @Override public String getFilename() { return "PRIVATE_BUYER.pdf"; }
      });
      var upload = new ObjectMapper().readTree(app.client().post().uri(base + "/documents")
          .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
          .header("Case-Access-Token", token).header("Idempotency-Key", "private-upload-key")
          .body(parts).retrieve().body(String.class));
      String document = upload.at("/document/id").asText();
      JsonNode extraction = null;
      for (int i = 0; i < 200; i++) {
        try { extraction = get(app.client(), base + "/documents/" + document + "/extractions/latest", token); break; }
        catch (HttpClientErrorException.NotFound waiting) { Thread.sleep(25); }
      }
      assertThat(extraction).isNotNull();
      var localUsage = opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT);
      assertThat(localUsage).hasSize(1);
      assertThat(localUsage.get(0).path("costUsd").decimalValue()).isEqualByComparingTo("0");
      assertThat(localUsage.get(0).path("unit").asText()).isEqualTo("PAGE");
      assertThat(localUsage.get(0).path("license").asText()).isEqualTo("LOCAL_PROCESSING");
      String candidate = extraction.path("candidates").get(0).path("id").asText();
      var scope = Map.of("extractionId", extraction.path("id").asText(), "candidateId", candidate);
      String requests = base + "/human-reviews";
      post(app.client(), base + "/consents", Map.of("purpose", "HUMAN_REVIEW", "granted", false, "policyVersion", "review.v1"), "decline", token);
      assertThatThrownBy(() -> post(app.client(), requests, scope, "no-consent", token))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      post(app.client(), base + "/consents", Map.of("purpose", "HUMAN_REVIEW", "granted", true, "policyVersion", "review.v1"), "grant", token);
      var request = post(app.client(), requests, scope, "request", token);
      String review = "/api/v1/operator/reviews/" + request.path("id").asText();
      assertThat(opGet(app.client(), "/api/v1/operator/reviews", REVIEWER)).hasSize(1);
      assertThatThrownBy(() -> opPost(app.client(), review + "/start", Map.of(), SUPPORT))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);
      post(app.client(), base + "/consents", Map.of("purpose", "HUMAN_REVIEW", "granted", false, "policyVersion", "review.v1"), "revoke", token);
      assertThat(opGet(app.client(), "/api/v1/operator/reviews", REVIEWER)).isEmpty();
      assertThatThrownBy(() -> opPost(app.client(), review + "/start", Map.of(), REVIEWER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      post(app.client(), base + "/consents", Map.of("purpose", "HUMAN_REVIEW", "granted", true, "policyVersion", "review.v1"), "regrant", token);
      assertThat(opGet(app.client(), "/api/v1/operator/reviews", REVIEWER)).isEmpty();
      var current = post(app.client(), requests, scope, "new-request", token);
      String currentReview = "/api/v1/operator/reviews/" + current.path("id").asText();
      SqlCapture.statements.clear();
      var grant = opPost(app.client(), currentReview + "/start", Map.of(), REVIEWER);
      assertPrivateColumnsNotSelected();
      assertThatThrownBy(() -> opGet(app.client(), currentReview + "/content", OTHER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThatThrownBy(() -> opPost(app.client(), currentReview + "/start", Map.of(), OTHER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThat(grant.path("expiresAt").asText()).isNotBlank();
      var content = opGet(app.client(), currentReview + "/content", REVIEWER);
      assertThat(content.path("id").asText()).isEqualTo(candidate);
      assertThat(content.toString()).doesNotContain("PRIVATE_BUYER", "objectKey", "accessToken", "candidates");
      assertThatThrownBy(() -> get(app.client(), base, REVIEWER)).isInstanceOf(HttpClientErrorException.NotFound.class);
      SqlCapture.statements.clear();
      var audit = opGet(app.client(), "/api/v1/operator/cases/" + id + "/audit", SUPPORT);
      assertPrivateColumnsNotSelected();
      assertThat(audit.findValuesAsText("action")).contains("REQUESTED", "STARTED", "CONTENT_READ");
      assertThat(audit.toString()).doesNotContain("originalWording", "normalizedValue", "PRIVATE_BUYER");
      opPost(app.client(), currentReview + "/complete", Map.of("outcome", "NEEDS_BUYER_CORRECTION"), REVIEWER);
      assertThatThrownBy(() -> opGet(app.client(), currentReview + "/content", REVIEWER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThat(get(app.client(), requests, token).findValuesAsText("outcome")).contains("NEEDS_BUYER_CORRECTION");
      var expiring = post(app.client(), requests, scope, "expiring", token);
      String expiringReview = "/api/v1/operator/reviews/" + expiring.path("id").asText();
      opPost(app.client(), expiringReview + "/start", Map.of(), REVIEWER);
      Thread.sleep(1100);
      assertThatThrownBy(() -> opGet(app.client(), expiringReview + "/content", REVIEWER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      assertThat(get(app.client(), requests, token).findValuesAsText("state")).contains("EXPIRED");
      var revoking = post(app.client(), requests, scope, "revoking", token);
      String revokingReview = "/api/v1/operator/reviews/" + revoking.path("id").asText();
      opPost(app.client(), revokingReview + "/start", Map.of(), REVIEWER);
      post(app.client(), base + "/consents", Map.of("purpose", "HUMAN_REVIEW", "granted", false, "policyVersion", "review.v1"), "revoke-started", token);
      assertThatThrownBy(() -> opGet(app.client(), revokingReview + "/content", REVIEWER))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      post(app.client(), base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      assertThat(opGet(app.client(), "/api/v1/operator/cases/" + id + "/usage", SUPPORT)).isEmpty();
      assertThat(opGet(app.client(), "/api/v1/operator/reviews", REVIEWER)).isEmpty();
    }
  }
  @Test void extractionContractFailureRetainsItsCategoryAtTheDocumentStage() throws Exception {
    var settings = new java.util.ArrayList<>(java.util.List.of(SETTINGS));
    settings.add("--document.storage.path=" + directory.resolve("contract-objects"));
    settings.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("contract", true,
        InvalidExtractionFixture.class, settings.toArray(String[]::new))) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();
      parts.add("file", new org.springframework.core.io.ByteArrayResource(quotePdf()) {
        @Override public String getFilename() { return "private.pdf"; }
      });
      var upload = new ObjectMapper().readTree(app.client().post().uri("/api/v1/cases/" + id + "/documents")
          .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
          .header("Case-Access-Token", token).header("Idempotency-Key", "contract-upload")
          .body(parts).retrieve().body(String.class));
      JsonNode operation = null;
      for (int i = 0; i < 200; i++) {
        operation = get(app.client(), "/api/v1/operations/" + upload.at("/operation/id").asText(), token);
        if (operation.path("stage").asText().equals("FAILED")) break;
        Thread.sleep(25);
      }
      assertThat(operation.path("stage").asText()).isEqualTo("FAILED");
      var diagnostics = opGet(app.client(), "/api/v1/operator/cases/" + id, SUPPORT);
      assertThat(diagnostics.at("/documents/0/failureCategory").asText()).isEqualTo("CONTRACT_FAILURE");
      assertThat(diagnostics.at("/documents/0/failedStage").asText()).isEqualTo("EXTRACTING");
      assertThat(diagnostics.toString()).doesNotContain("SENSITIVE_SCHEMA");
    }
  }
  @org.springframework.boot.test.context.TestConfiguration
  static class InvalidExtractionFixture {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    com.dealanalyzer.extraction.DealTermsExtractionProvider invalidExtractor() {
      return input -> new com.dealanalyzer.extraction.DealTermsExtractionProvider.ProviderExtraction(
          "fixture", "fixture", "SENSITIVE_SCHEMA", 1, java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
  }

  static byte[] quotePdf() throws Exception {
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
        content.beginText(); content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700); content.showText("DEALER QUOTE");
        content.newLineAtOffset(0, -20); content.showText("Selling Price: $20000.00"); content.endText();
      }
      pdf.save(out); return out.toByteArray();
    }
  }

  @Test void malformedSensitiveInputsDoNotReachLogsOrErrorResponses(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("privacy", false, SETTINGS)) {
      String secret = "PRIVATE_FINANCING_1HGCM82633A004352";
      var body = new ObjectMapper().valueToTree(CasePortabilityAcceptanceTest.buyingCase());
      ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("purchaseMethod", secret);
      try { post(app.client(), "/api/v1/cases", body); fail("Expected invalid request"); }
      catch (HttpClientErrorException error) {
        assertThat(error.getStatusCode().value()).isEqualTo(400);
        assertThat(error.getResponseBodyAsString()).doesNotContain(secret);
      }
      assertThat(output.getAll()).doesNotContain(secret);
    }
  }

  public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
    static final java.util.Queue<String> statements = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public String inspect(String sql) { statements.add(sql); return sql; }
  }
  static void assertPrivateColumnsNotSelected() {
    assertThat(SqlCapture.statements).isNotEmpty();
    assertThat(String.join("\n", SqlCapture.statements)).doesNotContain(
        "payload_json", "input_json", "vehicle_json", "dealer_offer_json", "access_token_hash", "receipt_hash",
        "original_wording", "normalized_value", "recognized_text", "metadata_json", "original_filename", "object_key", "idempotency_key");
  }

  static JsonNode opGet(RestClient client, String path, String token) throws Exception {
    return new ObjectMapper().readTree(client.get().uri(path).header("Authorization", "Bearer " + token)
        .retrieve().body(String.class));
  }
  static JsonNode opPost(RestClient client, String path, Object body, String token) throws Exception {
    return new ObjectMapper().readTree(client.post().uri(path).header("Authorization", "Bearer " + token)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
  }
}
