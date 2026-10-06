package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.awaitExtraction;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.find;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.twoPagePdf;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.validCase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.api.HttpAcceptanceHarness.RunningApplication;
import com.dealanalyzer.mandate.MandateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

class NegotiationMandateAcceptanceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void buyerApprovesImmutableMandatesWithinTheDealPlanAndRevocationExpiryAndPlanChangesApplyAtOnce() throws Exception {
    try (RunningApplication application = new HttpAcceptanceHarness(temporaryDirectory).startWithConfiguration(
        "mandates", true, DocumentExtractionAcceptanceTest.FixtureExtractionProvider.class,
        "--document.storage.path=" + temporaryDirectory.resolve("mandate-objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = application.client();
      JsonNode created = HttpAcceptanceHarness.post(client, "/api/v1/cases", validCase(), "create-case");
      String caseId = created.at("/case/id").asText(), token = created.get("accessToken").asText();
      String casePath = "/api/v1/cases/" + caseId;
      String mandates = casePath + "/mandates";

      // Without a Market Assessment the Buyer is told why, and approval is refused.
      JsonNode early = HttpAcceptanceHarness.get(client, mandates + "/proposal", token);
      assertThat(early.path("available").asBoolean()).isFalse();
      assertThat(early.path("unavailableReason").asText()).contains("market");
      assertThatThrownBy(() -> HttpAcceptanceHarness.post(client, mandates, Map.of("planVersion", 1,
          "targetMinor", 100, "ceilingMinor", 100, "approved", true), "too-early", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);

      verifyOfferAndAssessMarket(application, casePath, token);
      assertThat(HttpAcceptanceHarness.get(client, mandates + "/proposal", token)
          .path("unavailableReason").asText()).contains("Review price boundaries first");

      JsonNode plan = HttpAcceptanceHarness.post(client, casePath + "/price-plans",
          Map.of("maxSellingPriceMinor", 2_000_000), "plan", token);
      long planCeiling = plan.at("/payload/plan/sellingPrice/walkAwayMinor").asLong();
      JsonNode proposal = HttpAcceptanceHarness.get(client, mandates + "/proposal", token);
      assertThat(proposal.path("available").asBoolean()).isTrue();
      assertThat(proposal.path("planVersion").asInt()).isEqualTo(1);
      assertThat(proposal.path("ceilingMinor").asLong()).isEqualTo(planCeiling);
      long target = proposal.path("targetMinor").asLong();
      assertThat(target).isPositive().isLessThanOrEqualTo(planCeiling)
          .isLessThanOrEqualTo(plan.at("/payload/plan/sellingPrice/targetMinor").asLong());
      assertThat(proposal.has("autonomyModes")).isFalse();
      assertThat(proposal.path("disclosures").toString())
          .isEqualTo("[\"PAYING_CASH\",\"HAS_OUTSIDE_FINANCING\",\"FIRST_NAME\",\"PURCHASE_TIMELINE\"]");

      Map<String, Object> request = new HashMap<>(Map.of("planVersion", 1, "targetMinor", target - 20_000,
          "ceilingMinor", planCeiling - 10_000, "disclosures", List.of("PAYING_CASH", "FIRST_NAME"),
          "approved", true));
      for (var refused : List.of(
          Map.<String, Object>of("ceilingMinor", planCeiling + 1),
          Map.<String, Object>of("targetMinor", target + 1),
          Map.<String, Object>of("disclosures", List.of("PAYING_CASH", "HOME_ADDRESS")),
          Map.<String, Object>of("approved", false),
          Map.<String, Object>of("planVersion", 2),
          Map.<String, Object>of("expiresAt", Instant.now().plus(31, ChronoUnit.DAYS).toString()))) {
        var body = new HashMap<>(request);
        body.putAll(refused);
        assertThatThrownBy(() -> HttpAcceptanceHarness.post(client, mandates, body, "refused-" + refused.keySet(), token))
            .as(refused.toString()).isInstanceOf(HttpClientErrorException.BadRequest.class);
      }

      JsonNode first = HttpAcceptanceHarness.post(client, mandates, request, "approve-one", token);
      assertThat(first.path("version").asInt()).isEqualTo(1);
      assertThat(first.path("status").asText()).isEqualTo("ACTIVE");
      assertThat(first.path("approvedBy").asText()).isEqualTo("BUYER");
      assertThat(first.path("approvedAt").asText()).isNotBlank();
      assertThat(first.path("ceilingMinor").asLong()).isEqualTo(planCeiling - 10_000);
      assertThat(first.path("planCeilingMinor").asLong()).isEqualTo(planCeiling);
      assertThat(first.path("disclosures").toString()).isEqualTo("[\"PAYING_CASH\",\"FIRST_NAME\"]");
      // Ticket 84: there is no autonomy mode; a request naming one cannot enable delegated sending.
      assertThat(first.has("autonomyMode")).isFalse();
      assertThat(HttpAcceptanceHarness.post(client, mandates, request, "approve-one", token)).isEqualTo(first);
      var changed = new HashMap<>(request);
      changed.put("targetMinor", target - 30_000);
      assertThatThrownBy(() -> HttpAcceptanceHarness.post(client, mandates, changed, "approve-one", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);

      // Changing anything is a new approved version; the first stays as approved.
      JsonNode second = HttpAcceptanceHarness.post(client, mandates, changed, "approve-two", token);
      assertThat(second.path("version").asInt()).isEqualTo(2);
      assertThat(HttpAcceptanceHarness.get(client, mandates + "/1", token).path("status").asText()).isEqualTo("SUPERSEDED");
      assertThat(HttpAcceptanceHarness.get(client, mandates + "/1", token).path("targetMinor").asLong())
          .isEqualTo(target - 20_000);

      var service = application.context().getBean(MandateService.class);
      var jdbc = new JdbcTemplate(application.context().getBean(javax.sql.DataSource.class));
      assertThat(jdbc.queryForObject("select autonomy_mode from negotiation_mandates where id = ?", String.class, first.path("id").asText())).isNull();
      var delegated = new HashMap<>(changed);
      delegated.put("autonomyMode", "DELEGATED");
      // The field is not part of the request, so this replays the same approval and changes nothing.
      assertThat(HttpAcceptanceHarness.post(client, mandates, delegated, "approve-two", token)).isEqualTo(second);
      assertThat(jdbc.queryForObject("select count(*) from negotiation_mandates where case_id = ? and autonomy_mode is not null", Long.class, caseId)).isZero();
      // A mandate approved before ticket 84 keeps its stored mode and stays readable.
      jdbc.update("update negotiation_mandates set autonomy_mode = 'SUPERVISED' where id = ?", first.path("id").asText());
      assertThat(HttpAcceptanceHarness.get(client, mandates + "/1", token).path("targetMinor").asLong()).isEqualTo(target - 20_000);
      assertThat(service.activeMandate(caseId)).get().extracting(MandateService.Mandate::version).isEqualTo(2);
      Instant expiresAt = Instant.parse(second.path("expiresAt").asText());
      assertThat(service.expiringBefore(expiresAt.plusSeconds(1))).extracting(MandateService.Mandate::id)
          .contains(second.path("id").asText());
      assertThat(service.expiringBefore(expiresAt.minusSeconds(1))).extracting(MandateService.Mandate::id)
          .doesNotContain(second.path("id").asText());
      // Expiry applies at its instant and is audited once.
      assertThat(service.activeMandate(caseId, expiresAt)).isEmpty();
      assertThat(service.recordExpirations(expiresAt.plusSeconds(1))).isEqualTo(1);
      assertThat(service.recordExpirations(expiresAt.plusSeconds(2))).isZero();
      assertThat(jdbc.queryForObject("select actor from audit_events where case_id = ? and event_type = 'MANDATE_EXPIRED'",
          String.class, caseId)).isEqualTo("SYSTEM");

      // Revocation is immediate; only the current version can be revoked.
      assertThatThrownBy(() -> HttpAcceptanceHarness.post(client, mandates + "/1/revocation", Map.of(), "revoke-old", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      JsonNode revoked = HttpAcceptanceHarness.post(client, mandates + "/2/revocation", Map.of(), "revoke", token);
      assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
      assertThat(revoked.path("revokedBy").asText()).isEqualTo("BUYER");
      assertThat(service.activeMandate(caseId)).isEmpty();
      assertThat(HttpAcceptanceHarness.post(client, mandates + "/2/revocation", Map.of(), "revoke", token)).isEqualTo(revoked);
      assertThat(HttpAcceptanceHarness.get(client, mandates, token)).extracting(node -> node.path("status").asText())
          .containsExactly("REVOKED", "SUPERSEDED");
      assertThat(jdbc.queryForList("select event_type from audit_events where case_id = ? and event_type like 'MANDATE_%' order by created_at, event_type",
          String.class, caseId)).containsExactlyInAnyOrder("MANDATE_APPROVED", "MANDATE_APPROVED", "MANDATE_REVOKED", "MANDATE_EXPIRED");

      // A newer Deal Plan replaces the one a mandate was approved against; the mandate stops applying at once.
      JsonNode third = HttpAcceptanceHarness.post(client, mandates, request, "approve-three", token);
      assertThat(third.path("status").asText()).isEqualTo("ACTIVE");
      JsonNode lowerPlan = HttpAcceptanceHarness.post(client, casePath + "/price-plans",
          Map.of("maxSellingPriceMinor", planCeiling - 50_000), "plan-two", token);
      long lowerCeiling = lowerPlan.at("/payload/plan/sellingPrice/walkAwayMinor").asLong();
      assertThat(lowerCeiling).isLessThan(third.path("ceilingMinor").asLong());
      assertThat(HttpAcceptanceHarness.get(client, mandates + "/3", token).path("status").asText()).isEqualTo("PLAN_CHANGED");
      assertThat(service.activeMandate(caseId)).isEmpty();
      assertThat(service.expiringBefore(Instant.parse(third.path("expiresAt").asText()).plusSeconds(1)))
          .extracting(MandateService.Mandate::id).doesNotContain(third.path("id").asText());
      JsonNode newProposal = HttpAcceptanceHarness.get(client, mandates + "/proposal", token);
      assertThat(newProposal.path("planVersion").asInt()).isEqualTo(2);
      assertThat(newProposal.path("ceilingMinor").asLong()).isEqualTo(lowerCeiling);
      assertThatThrownBy(() -> HttpAcceptanceHarness.post(client, mandates, request, "approve-stale", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      var current = new HashMap<>(request);
      current.putAll(Map.of("planVersion", 2, "ceilingMinor", lowerCeiling,
          "targetMinor", Math.min(newProposal.path("targetMinor").asLong(), lowerCeiling)));
      JsonNode fourth = HttpAcceptanceHarness.post(client, mandates, current, "approve-four", token);
      assertThat(fourth.path("status").asText()).isEqualTo("ACTIVE");
      assertThat(service.activeMandate(caseId)).get().extracting(MandateService.Mandate::version).isEqualTo(4);

      assertThatThrownBy(() -> HttpAcceptanceHarness.get(client, mandates, "wrong-token"))
          .isInstanceOf(HttpClientErrorException.class);
      assertThat(jdbc.queryForObject("select count(*) from negotiation_mandates where case_id = ?", Integer.class, caseId))
          .isEqualTo(4);
    }
  }

  private static void verifyOfferAndAssessMarket(RunningApplication application, String casePath, String token) throws Exception {
    var client = application.client();
    HttpAcceptanceHarness.post(client, casePath + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
        Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000)), "vehicle", token);
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new DocumentExtractionAcceptanceTest.NamedBytes("initial.pdf", twoPagePdf()));
    JsonNode upload = new ObjectMapper().readTree(client.post().uri(casePath + "/documents")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", "upload").header("Case-Access-Token", token)
        .body(parts).retrieve().body(String.class));
    awaitExtraction(application, upload.at("/operation/id").asText(), token);
    String documentPath = casePath + "/documents/" + upload.at("/document/id").asText();
    JsonNode extraction = HttpAcceptanceHarness.get(client, documentPath + "/extractions/latest", token);
    HttpAcceptanceHarness.post(client, documentPath + "/extractions/" + extraction.get("id").asText() + "/corrections",
        Map.of("targetType", "CANDIDATE", "targetId", find(extraction.get("candidates"), "field", "AMOUNT_FINANCED").get("id").asText(),
            "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "correct", token);
    List<String> candidates = new ArrayList<>(), lines = new ArrayList<>();
    extraction.get("candidates").forEach(candidate -> candidates.add(candidate.get("id").asText()));
    extraction.get("lineItems").forEach(line -> lines.add(line.get("id").asText()));
    HttpAcceptanceHarness.post(client, casePath + "/dealer-offers", Map.of("extractionId", extraction.get("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "verify", token);
    HttpAcceptanceHarness.post(client, casePath + "/cash-economics", Map.of(), "economics", token);
    HttpAcceptanceHarness.post(client, casePath + "/market-assessments", Map.of(), "market", token);
  }
}
