package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.quote.DealerDeclinedService;
import com.dealanalyzer.relay.DealerMailRelay;
import com.dealanalyzer.relay.FakeDealerMailRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

class DealerDeclinedAcceptanceTest {
  @TempDir Path directory;

  @Test void ruleAndBuyerDecisionsCancelCounteroffersAndOnlyTheirPermittedClearanceRestartsThem() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("dealer-declined", true,
        FixtureExtractionProvider.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, path = base + "/negotiation-conversation";
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var provider = (FakeDealerMailRelay) app.context().getBean(DealerMailRelay.class);
      OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
      jdbc.update("update vehicles set vin = '1HGCM82633A004352' where case_id = ?", caseId);
      String dealer = UUID.randomUUID().toString();
      jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'dealer', 'Dealer', current_timestamp)", dealer, caseId);
      jdbc.update("""
          insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
          values (?, ?, ?, 'listing', 'https://dealer.example/vehicle/42', '1HGCM82633A004352', 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
          """, UUID.randomUUID().toString(), caseId, dealer);
      var proposal = get(client, base + "/mandates/proposal", token);
      post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
          "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), "mandate", token);
      var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote", token);
      String quoteId = quote.path("id").asText();
      post(client, base + "/quote-requests/" + quoteId + "/approval", Map.of("approved", true, "text", quote.at("/gatedDraft/draft/text").asText()), "quote-send", token);
      post(client, base + "/analyses", Map.of(), "analysis-1", token);
      String extractionId = jdbc.queryForObject("select extraction_id from dealer_offers where case_id = ?", String.class, caseId);
      String documentId = jdbc.queryForObject("select document_id from document_extractions where id = ?", String.class, extractionId);
      var extraction = get(client, base + "/documents/" + documentId + "/extractions/latest", token);
      var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
      extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
      extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
      var verification = Map.of("extractionId", extractionId, "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines);

      var first = draft(client, path, token, quoteId, "first");
      send(client, path, token, first);
      assertThat(get(client, path, token).path("dealerDeclined").isNull()).isTrue(); // awaiting a verified response
      post(client, base + "/dealer-offers", verification, "response-1", token);
      assertThat(get(client, path, token).path("dealerDeclined").isNull()).isTrue(); // one round is insufficient
      post(client, base + "/cash-economics", Map.of(), "cash-2", token);
      post(client, base + "/market-assessments", Map.of(), "market-2", token);
      post(client, base + "/analyses", Map.of(), "analysis-2", token);
      var second = draft(client, path, token, quoteId, "second");
      send(client, path, token, second);
      var queued = draft(client, path, token, quoteId, "queued");
      assertThat(get(client, path, token).path("dealerDeclined").isNull()).isTrue();
      post(client, base + "/dealer-offers", verification, "response-2", token);

      // Send and draft checks enforce the rule even before the Buyer refreshes the view.
      assertThatThrownBy(() -> send(client, path, token, queued)).isInstanceOf(HttpClientErrorException.BadRequest.class)
          .hasMessageContaining("dealer has declined");
      assertThatThrownBy(() -> draft(client, path, token, quoteId, "blocked")).isInstanceOf(HttpClientErrorException.BadRequest.class)
          .hasMessageContaining("dealer has declined");
      var stoppedCounters = get(client, path, token);
      assertThat(stoppedCounters.at("/dealerDeclined/source").asText()).isEqualTo("RULE");
      assertThat(stoppedCounters.at("/dealerDeclined/rounds").asInt()).isEqualTo(2);
      assertThat(stoppedCounters.at("/dealerDeclined/lastVerifiedSellingPriceMinor").asLong()).isEqualTo(2500000);
      assertThat(stoppedCounters.at("/dealerDeclined/currency").asText()).isEqualTo("USD");
      assertThat(stoppedCounters.path("negotiationState").asText()).isEqualTo("AWAITING_DEALER");
      assertThat(jdbc.queryForObject("select cancel_reason from negotiation_drafts where id = ?", String.class, queued.path("id").asText()))
          .isEqualTo("DEALER_DECLINED");
      assertThat(provider.sent()).hasSize(3);
      // The decision retains its recorded threshold across configuration changes.
      var tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
      var changed = new DealerDeclinedService(jdbc, app.context().getBean(ObjectMapper.class), 3);
      assertThat(tx.execute(status -> changed.current(caseId)).rounds()).isEqualTo(2);
      assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ? and aggregate_type = 'DEALER_DECLINED'", Integer.class, caseId)).isEqualTo(1);
      assertThatThrownBy(() -> client.delete().uri(path + "/dealer-declined").header("Case-Access-Token", "wrong").retrieve().toBodilessEntity())
          .isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> post(client, path + "/dealer-declined", Map.of(), "wrong", "wrong"))
          .isInstanceOf(HttpClientErrorException.class);
      assertThat(client.delete().uri(path + "/dealer-declined").header("Case-Access-Token", token).retrieve().body(JsonNode.class)
          .at("/dealerDeclined/source").asText()).isEqualTo("RULE"); // Buyer undo cannot erase the rule

      String corrections = base + "/documents/" + documentId + "/extractions/" + extractionId + "/corrections";
      String priceCandidate = find(extraction.path("candidates"), "field", "SELLING_PRICE").path("id").asText();
      post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", priceCandidate,
          "normalizedValue", "2490000", "normalizedUnit", "USD_MINOR"), "lower-price", token);
      assertThat(get(client, path, token).at("/dealerDeclined/source").asText()).isEqualTo("RULE"); // unverified correction cannot clear it
      post(client, base + "/dealer-offers", verification, "lower-verified", token);
      assertThat(get(client, path, token).path("dealerDeclined").isNull()).isTrue();
      post(client, base + "/cash-economics", Map.of(), "cash-4", token);
      post(client, base + "/market-assessments", Map.of(), "market-4", token);
      post(client, base + "/analyses", Map.of(), "analysis-4", token);
      var resumed = draft(client, path, token, quoteId, "resumed");
      assertThat(resumed.path("state").asText()).isEqualTo("PROPOSED");

      var buyer = post(client, path + "/dealer-declined", Map.of(), "buyer", token);
      assertThat(buyer.at("/dealerDeclined/source").asText()).isEqualTo("BUYER");
      assertThat(buyer.at("/dealerDeclined/lastVerifiedSellingPriceMinor").asLong()).isEqualTo(2490000);
      assertThat(jdbc.queryForObject("select cancel_reason from negotiation_drafts where id = ?", String.class, resumed.path("id").asText()))
          .isEqualTo("DEALER_DECLINED");
      post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", priceCandidate,
          "normalizedValue", "2480000", "normalizedUnit", "USD_MINOR"), "lower-again", token);
      post(client, base + "/dealer-offers", verification, "buyer-lower-verified", token);
      assertThat(get(client, path, token).at("/dealerDeclined/source").asText()).isEqualTo("BUYER");
      assertThatThrownBy(() -> draft(client, path, token, quoteId, "buyer-blocked")).isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> send(client, path, token, resumed)).isInstanceOf(HttpClientErrorException.BadRequest.class);
      var cleared = client.delete().uri(path + "/dealer-declined").header("Case-Access-Token", token).retrieve().body(JsonNode.class);
      assertThat(cleared.path("dealerDeclined").isNull()).isTrue();
      assertThat(jdbc.queryForList("select actor from audit_events where case_id = ? and aggregate_type = 'DEALER_DECLINED' order by aggregate_version",
          String.class, caseId)).containsExactly("RULE", "RULE", "BUYER", "BUYER");
      post(client, base + "/cash-economics", Map.of(), "cash-5", token);
      post(client, base + "/market-assessments", Map.of(), "market-5", token);
      post(client, base + "/analyses", Map.of(), "analysis-5", token);
      assertThat(draft(client, path, token, quoteId, "after-undo").path("state").asText()).isEqualTo("PROPOSED");
      post(client, path + "/dealer-declined", Map.of(), "buyer-stop", token);
      assertThat(post(client, path + "/stop", Map.of(), "stop", token).path("negotiationState").asText()).isEqualTo("STOPPED");

      var exported = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] bytes = client.get().uri(base + "/exports/" + exported.path("id").asText()).header("Case-Access-Token", token).retrieve().body(byte[].class);
      try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
        String data = null;
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
          if (entry.getName().equals("case-data.json")) data = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(data).contains("DEALER_DECLINED_RECORDED", "DEALER_DECLINED_CLEARED", "lastVerifiedSellingPriceMinor");
        var audit = new ObjectMapper().readTree(data).path("audit_events");
        var decision = find(audit, "event_type", "DEALER_DECLINED_RECORDED").at("/metadata_json/decision");
        assertThat(decision.path("rounds").asInt()).isEqualTo(2);
        assertThat(decision.path("currency").asText()).isEqualTo("USD");
      }
      post(client, base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ?", Integer.class, caseId)).isZero();
    }
  }

  private static JsonNode draft(RestClient client, String path, String token, String quote, String key) throws Exception {
    return post(client, path + "/drafts", Map.of("quoteRequestId", quote), key, token);
  }
  private static JsonNode send(RestClient client, String path, String token, JsonNode draft) throws Exception {
    return post(client, path + "/drafts/" + draft.path("id").asText() + "/approval",
        Map.of("approved", true, "text", draft.at("/revisions/0/text").asText()), "send-" + draft.path("id").asText(), token);
  }
}
