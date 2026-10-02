package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.ServiceOperationsAcceptanceTest.*;
import com.dealanalyzer.purchase.PurchaseService;
import com.dealanalyzer.pilot.PilotMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class OneTimePurchaseAcceptanceTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> REPORT_ENDPOINTS = Set.of(
      "/api/v1/cases/{caseId}/reports/latest", "/api/v1/cases/{caseId}/reports/{version}",
      "/api/v1/cases/{caseId}/reports/{version}/explanation", "/api/v1/cases/{caseId}/reports/{version}/negotiation",
      "/api/v1/cases/{caseId}/reports/{version}/negotiation/replies",
      "/api/v1/cases/{caseId}/deal-reviews/latest", "/api/v1/cases/{caseId}/deal-reviews/{version}");
  private String[] settings(boolean active) throws Exception {
    var settings = new ArrayList<>(List.of(SETTINGS));
    settings.add("--document.storage.path=" + directory.resolve("objects"));
    settings.add("--document.storage.encryption-key=0123456789abcdef0123456789abcdef");
    if (active) {
      ObjectNode config;
      try (var input = getClass().getResourceAsStream("/purchase/offer.v1.json")) { config = (ObjectNode) JSON.readTree(input); }
      config.putObject("approval").put("experimentKey", "synthetic-price-test").put("amountMinor", 1700)
          .put("currency", "USD").put("approver", "test-operator").put("approvedAt", "2026-09-01T00:00:00Z")
          .put("rationale", "Synthetic experiment price, not copied from a competitor's advertised price.")
          .put("notCopiedFromCompetitorAdvertisedPrice", true);
      var path = directory.resolve("approved-offer.json"); Files.writeString(path, config.toString());
      settings.add("--purchase.offer-config=" + path.toUri());
    }
    return settings.toArray(String[]::new);
  }
  @Test void activeOfferGatesEveryReportEndpointAndPurchaseRefundAndDeletionAreIdempotent() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("purchase", true, settings(true))) {
      var client = app.client();
      var offer = get(client, "/api/v1/offer");
      assertThat(offer.path("status").asText()).isEqualTo("AVAILABLE");
      assertThat(offer.at("/price/amountMinor").asInt()).isEqualTo(1700);
      assertThat(offer.path("jurisdiction").isNull()).isTrue();
      var fresh = post(client, "/api/v1/cases", PilotFeedbackAcceptanceTest.buyingCase());
      String freshBase = "/api/v1/cases/" + fresh.at("/case/id").asText();
      String freshToken = fresh.path("accessToken").asText();
      assertThatThrownBy(() -> get(client, freshBase + "/reports/latest", freshToken))
          .isInstanceOf(HttpClientErrorException.class).satisfies(error -> assertThat(((HttpClientErrorException) error).getStatusCode().value()).isEqualTo(402));
      for (String endpoint : List.of("/analyses", "/market-assessments"))
        assertThatThrownBy(() -> post(client, freshBase + endpoint, Map.of(), "work", freshToken))
            .isInstanceOf(HttpClientErrorException.class).satisfies(error -> assertThat(((HttpClientErrorException) error).getStatusCode().value()).isEqualTo(402));
      JsonNode fixture;
      try (var input = getClass().getResourceAsStream("/fixtures/golden/cash-deal-standard.json")) { fixture = JSON.readTree(input); }
      var started = post(client, "/api/v1/cases/synthetic", fixture.path("input"));
      String id = started.at("/case/id").asText(), token = started.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      awaitCompleted(client, started.at("/operation/id").asText(), token);
      assertThat(get(client, base + "/entitlement", token).path("reportAccess").asText()).isEqualTo("PURCHASE_REQUIRED");
      assertThatThrownBy(() -> get(client, base + "/entitlement", "wrong")).isInstanceOf(HttpClientErrorException.NotFound.class);
      var mappings = app.context().getBean(RequestMappingHandlerMapping.class).getHandlerMethods();
      var actual = new HashSet<String>();
      mappings.forEach((mapping, method) -> mapping.getPatternValues().stream()
          .filter(path -> path.contains("/reports") || path.contains("/deal-reviews"))
          .forEach(actual::add));
      assertThat(actual).as("New report-bearing endpoints must be added to the purchase gate coverage").isEqualTo(REPORT_ENDPOINTS);
      for (String path : REPORT_ENDPOINTS) {
        String url = path.replace("{caseId}", id).replace("{version}", "1");
        try {
          if (path.endsWith("/replies")) post(client, url, Map.of("dealerResponse", "test"), UUID.randomUUID().toString(), token);
          else get(client, url, token);
          fail("Paid endpoint bypassed gate: " + path);
        } catch (HttpClientErrorException error) {
          assertThat(error.getStatusCode().value()).as(path).isEqualTo(402);
          assertThat(JSON.readTree(error.getResponseBodyAsString())).isEqualTo(JSON.readTree("{\"code\":\"PURCHASE_REQUIRED\",\"offerId\":\"one-time-case.v1\"}"));
        }
      }
      for (String format : List.of("HTML", "PDF"))
        assertThatThrownBy(() -> post(client, base + "/exports", Map.of("format", format, "reportVersion", 1), "export-" + format, token))
            .isInstanceOf(HttpClientErrorException.class).satisfies(error -> assertThat(((HttpClientErrorException) error).getStatusCode().value()).isEqualTo(402));
      assertThatThrownBy(() -> post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "zip", token))
          .isInstanceOf(HttpClientErrorException.class).satisfies(error -> assertThat(((HttpClientErrorException) error).getStatusCode().value()).isEqualTo(402));
      assertThatThrownBy(() -> post(client, base + "/purchases", Map.of("offerId", "stale"), "stale", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class).hasMessageContaining("OFFER_CHANGED");
      assertThatThrownBy(() -> post(client, base + "/purchases", Map.of("offerId", "one-time-case.v1", "paymentCredential", "synthetic-rejected-value"), "credential", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> client.post().uri(base + "/purchases").header("Case-Access-Token", token)
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("offerId", "one-time-case.v1")).retrieve().toBodilessEntity())
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      var first = client.post().uri(base + "/purchases").header("Case-Access-Token", token).header("Idempotency-Key", "purchase")
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("offerId", "one-time-case.v1")).retrieve().toEntity(String.class);
      assertThat(first.getStatusCode().value()).isEqualTo(201);
      var purchase = JSON.readTree(first.getBody());
      var replay = client.post().uri(base + "/purchases").header("Case-Access-Token", token).header("Idempotency-Key", "purchase")
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("offerId", "one-time-case.v1")).retrieve().toEntity(String.class);
      assertThat(replay.getStatusCode().value()).isEqualTo(200);
      assertThat(JSON.readTree(replay.getBody())).isEqualTo(purchase);
      assertThat(post(client, base + "/purchases", Map.of("offerId", "one-time-case.v1"), "second-key", token)).isEqualTo(purchase);
      assertThat(post(client, base + "/purchases", Map.of("offerId", "one-time-case.v1"), "second-key", token)).isEqualTo(purchase);
      assertThat(purchase.path("checkout").asText()).isEqualTo("DEVELOPMENT_NO_CHARGE");
      assertThat(get(client, base + "/reports/latest", token).at("/analysis/economics/outTheDoorTotal/minorUnits").asLong()).isEqualTo(2847500L);
      String refund = "/api/v1/operator/purchases/" + purchase.path("purchaseId").asText() + "/refunds";
      assertThatThrownBy(() -> opPost(client, refund, Map.of(), token)).isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> opPost(client, refund, Map.of(), REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      opPost(client, refund, Map.of(), SUPPORT); opPost(client, refund, Map.of(), SUPPORT);
      assertThat(get(client, base + "/entitlement", token).path("reportAccess").asText()).isEqualTo("PURCHASED");
      assertThat(get(client, base + "/reports/latest", token).path("version").asInt()).isEqualTo(1);
      var metrics = app.context().getBean(PilotMetrics.class).snapshot();
      assertThat(((Map<?, ?>) metrics.get("paidCases")).get("numerator")).isEqualTo(1L);
      assertThat(((Map<?, ?>) metrics.get("refunds")).get("numerator")).isEqualTo(1L);
      assertThat(((Map<?, ?>) metrics.get("refunds")).get("denominator")).isEqualTo(1L);
      post(client, base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      var manager = app.context().getBean(EntityManager.class);
      assertThat(((Number) manager.createNativeQuery("select count(*) from case_purchases").getSingleResult()).longValue()).isZero();
      assertThat(((Number) manager.createNativeQuery("select count(*) from case_purchase_requests").getSingleResult()).longValue()).isZero();
      var after = app.context().getBean(PilotMetrics.class).snapshot();
      assertThat(after.get("paidCases")).isEqualTo(metrics.get("paidCases"));
      assertThat(after.get("refunds")).isEqualTo(metrics.get("refunds"));
    }
  }
  @Test void shippedConfigurationKeepsFreeFlowAndPublicSampleUsesGoldenPipeline() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("free", false, settings(false))) {
      assertThat(get(app.client(), "/api/v1/offer")).isEqualTo(JSON.readTree("{\"status\":\"UNAVAILABLE\"}"));
      var created = post(app.client(), "/api/v1/cases", PilotFeedbackAcceptanceTest.buyingCase());
      String base = "/api/v1/cases/" + created.at("/case/id").asText(), token = created.path("accessToken").asText();
      assertThat(get(app.client(), base + "/entitlement", token).path("reportAccess").asText()).isEqualTo("FREE_NO_OFFER");
      assertThatThrownBy(() -> post(app.client(), base + "/purchases", Map.of("offerId", "one-time-case.v1"), "purchase", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class).hasMessageContaining("NO_ACTIVE_OFFER");
      var sample = get(app.client(), "/api/v1/sample-report");
      assertThat(sample.path("label").asText()).isEqualTo("EXAMPLE");
      assertThat(sample.path("source").asText()).isEqualTo("SYNTHETIC_CASE");
      assertThat(sample.at("/redaction/version").asText()).isEqualTo("sample-redaction.v1");
      assertThat(sample.at("/report/analysis/economics/outTheDoorTotal/minorUnits").asLong()).isEqualTo(2847500L);
      assertThat(sample.toString()).doesNotContain("1HGCM82633A004352", "27601", "27604", "Synthetic Dealer Offer");
    }
  }
  @Test void channelPaidCostSurvivesDeletionAndDocumentOnlyArchivesRemainFree() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("paid-channel", false, settings(true))) {
      var client = app.client();
      String channels = "/api/v1/operator/pilot/channels";
      client.put().uri(channels + "/fee-guide").header("Authorization", "Bearer " + SUPPORT)
          .contentType(MediaType.APPLICATION_JSON).body(Map.of("kind", "EXPLAINER_CONTENT", "active", true))
          .retrieve().toBodilessEntity();
      opPost(client, channels + "/fee-guide/spend", Map.of("spendId", UUID.randomUUID().toString(),
          "periodStart", "2026-10-01", "periodEnd", "2026-10-31", "amountMinor", 5000, "currency", "USD"), SUPPORT);
      var created = post(client, "/api/v1/cases", AcquisitionChannelAcceptanceTest.buyingCase("fee-guide"));
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + id;
      var archive = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "docs", token);
      assertThat(client.get().uri(base + "/exports/" + archive.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class)).isNotEmpty();
      post(client, base + "/purchases", Map.of("offerId", "one-time-case.v1"), "purchase", token);
      var metrics = app.context().getBean(PilotMetrics.class);
      JsonNode before = JSON.valueToTree(metrics.channels());
      var row = before.path("channels").get(1).path("costPerPaidCase");
      assertThat(row.path("paidCases").asLong()).isEqualTo(1);
      assertThat(row.path("spendMinor").asLong()).isEqualTo(5000);
      assertThat(row.path("costMinor").asDouble()).isEqualTo(5000.0);
      assertThat(row.path("status").asText()).isEqualTo("MEASURED");
      post(client, base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker.class).runOnce();
      JsonNode after = JSON.valueToTree(metrics.channels());
      assertThat(after.path("channels").get(1).path("costPerPaidCase")).isEqualTo(row);
    }
  }
}
