package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.outbound.OutboundDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

class OutboundDraftAcceptanceTest {
  @TempDir Path directory;

  @Test void decisionsAreImmutableAuthorizedExportedAndErasedAndUseCurrentMandates() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("outbound", true,
        FixtureExtractionProvider.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, path = base + "/outbound-drafts";
      var jdbc = new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
      // New cases discard ZIP after jurisdiction resolution; cover retained legacy ZIPs too.
      jdbc.update("update analysis_cases set buyer_zip = '27601' where id = ?", caseId);
      String dealer = UUID.randomUUID().toString();
      jdbc.update("insert into dealers (id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'test', 'Dealer', current_timestamp)", dealer, caseId);
      jdbc.update("insert into case_recovery_contacts (case_id, email, expires_at) values (?, 'alice@example.com', current_timestamp + interval '1' day)", caseId);
      var question = Map.of("dealerId", dealer, "text", "Could you provide a current itemized written offer?");
      var early = post(client, path, question, "early", token);
      assertThat(early.at("/decision/pass").asBoolean()).isFalse();
      assertThat(early.at("/decision/reasons/0/code").asText()).isEqualTo("NO_ACTIVE_MANDATE");
      assertThatThrownBy(() -> post(client, path, question, "wrong-token", "wrong"))
          .isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> get(client, path, "wrong"))
          .isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> post(client, path, Map.of("dealerId", UUID.randomUUID().toString(), "text", question.get("text")), "wrong-dealer", token))
          .isInstanceOf(HttpClientErrorException.NotFound.class);

      verifyAndPlan(app, base, token);
      var proposal = get(client, base + "/mandates/proposal", token);
      long target = proposal.path("targetMinor").asLong(), ceiling = proposal.path("ceilingMinor").asLong();
      var mandate = post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(),
          "targetMinor", target, "ceilingMinor", ceiling, "approved", true), "mandate", token);
      var passed = post(client, path, question, "pass", token);
      assertThat(passed.at("/decision/pass").asBoolean()).isTrue();
      assertThat(passed.at("/decision/gateVersion").asText()).isEqualTo("outbound-gate.v4");
      assertThat(passed.path("mandateId")).isEqualTo(mandate.path("id"));
      assertThat(post(client, path, question, "pass", token)).isEqualTo(passed);
      assertThat(get(client, path + "/" + passed.path("id").asText(), token)).isEqualTo(passed);
      assertThatThrownBy(() -> post(client, path, Map.of("dealerId", dealer, "text", "Changed"), "pass", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      String price = "Would you consider USD " + java.math.BigDecimal.valueOf(target, 2).toPlainString()
          + " as the selling price in a revised written offer?";
      assertThat(post(client, path, Map.of("dealerId", dealer, "text", price), "price", token).at("/decision/pass").asBoolean()).isTrue();
      String excessive = "Would you consider USD " + java.math.BigDecimal.valueOf(ceiling + 50000, 2).toPlainString()
          + " as the selling price in a revised written offer?";
      var blocked = post(client, path, Map.of("dealerId", dealer, "text", excessive), "ceiling", token);
      assertThat(blocked.at("/decision/pass").asBoolean()).isFalse();
      assertThat(blocked.at("/decision/reasons").toString()).contains("ABOVE_CEILING", "walk-away ceiling");
      var commitment = post(client, path, Map.of("dealerId", dealer, "text", "the buyer will take it, deposit to follow"), "commit", token);
      assertThat(commitment.at("/decision/reasons").toString()).contains("COMMITMENT");
      var leak = post(client, path, Map.of("dealerId", dealer, "text", "My phone number is 2125551234."), "leak", token);
      assertThat(leak.at("/decision/reasons").toString()).contains("PERSONAL_DATA");

      var service = app.context().getBean(OutboundDraftService.class);
      assertThat(service.context(caseId).verifiedAmountsMinor()).contains(2_680_000L);
      assertThat(service.context(caseId).personalValues()).contains("alice@example.com", "27601");
      assertThat(jdbc.queryForList("select context_json from outbound_drafts where case_id = ?", String.class, caseId))
          .allSatisfy(snapshot -> assertThat(snapshot).doesNotContain("alice@example.com", "personalValues", "27601"));
      post(client, base + "/mandates/1/revocation", Map.of(), "revoke", token);
      assertThat(post(client, path, question, "revoked", token).at("/decision/pass").asBoolean()).isFalse();
      // The old decision remains a snapshot, rather than silently changing after revocation.
      assertThat(get(client, path + "/" + passed.path("id").asText(), token)).isEqualTo(passed);
      assertThat(get(client, path, token).size()).isEqualTo(7);

      var archive = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "archive", token);
      byte[] bytes = client.get().uri(base + "/exports/" + archive.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      String data = null;
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
          if (entry.getName().equals("case-data.json")) data = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      assertThat(data).contains("outbound_drafts", "outbound-gate.v4", "ABOVE_CEILING", passed.path("id").asText())
          .doesNotContain(token, "request_fingerprint", "idempotency_key");
      post(client, base + "/deletion", Map.of(), "delete", token);
      assertThatThrownBy(() -> get(client, path, token)).isInstanceOf(HttpClientErrorException.NotFound.class);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      assertThat(get(client, base + "/deletion", token).path("state").asText()).isEqualTo("DELETED");
      assertThat(jdbc.queryForObject("select count(*) from outbound_drafts where case_id = ?", Integer.class, caseId)).isZero();
    }
  }

  static void verifyAndPlan(RunningApplication app, String base, String token) throws Exception {
    var client = app.client();
    post(client, base + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
        Map.of("year", 2021, "make", "Honda", "model", "Accord", "trim", "EX-L", "mileage", 38000)), "vehicle", token);
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes("initial.pdf", twoPagePdf()));
    var upload = new ObjectMapper().readTree(client.post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", "upload").header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    awaitExtraction(app, upload.at("/operation/id").asText(), token);
    String document = base + "/documents/" + upload.at("/document/id").asText();
    var extraction = get(client, document + "/extractions/latest", token);
    post(client, document + "/extractions/" + extraction.path("id").asText() + "/corrections",
        Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
            "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "correct", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "verified", token);
    post(client, base + "/cash-economics", Map.of(), "cash", token);
    post(client, base + "/market-assessments", Map.of(), "market", token);
    post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 2_000_000), "plan", token);
  }
}
