package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.relay.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

class AgreedPriceAcceptanceTest {
  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    com.dealanalyzer.extraction.DealTermsExtractionProvider itemizedProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        var candidates = new ArrayList<>(extracted.candidates());
        candidates.add(new com.dealanalyzer.extraction.DealTermsExtractionProvider.Candidate("ADD_ON_TOTAL", "Add-ons $0", "0", "USD_MINOR",
            new com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion(2, .08, .16, .55, .04), .99, true));
        return new com.dealanalyzer.extraction.DealTermsExtractionProvider.ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(),
            extracted.schemaVersion(), extracted.expectedPageCount(), candidates, extracted.lineItems(), extracted.documentWarnings());
      };
    }
  }
  @TempDir Path directory;
  @Test void onlyCurrentWrittenItemizationCanBecomeAnAuditedBaselineAndAgreement() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("agreed", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, path = base + "/quote-acceptance";
      var jdbc = app.context().getBean(JdbcTemplate.class);
      OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
      jdbc.update("update vehicles set vin = '1HGCM82633A004352' where case_id = ?", caseId);
      String dealer = UUID.randomUUID().toString();
      jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'dealer', 'Dealer', current_timestamp)", dealer, caseId);
      jdbc.update("""
          insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
          values (?, ?, ?, 'listing', 'https://dealer.example/vehicle/42', '1HGCM82633A004352', 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
          """, UUID.randomUUID().toString(), caseId, dealer);
      approveMandate(client, base, token, "mandate1");
      var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote", token);
      post(client, base + "/quote-requests/" + quote.path("id").asText() + "/approval", approval(quote), "send", token);
      String quoteId = quote.path("id").asText();
      var provider = (FakeDealerMailRelay) app.context().getBean(DealerMailRelay.class);
      assertThatThrownBy(() -> get(client, path, "wrong")).isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> post(client, path, Map.of("quoteRequestId", quoteId), "wrong", "wrong"))
          .isInstanceOf(HttpClientErrorException.class);
      assertThatThrownBy(() -> client.get().uri(path + "/summary").header("Case-Access-Token", token).retrieve().body(String.class))
          .isInstanceOf(HttpClientErrorException.NotFound.class);
      var partial = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", 1), "partial", token);
      assertThat(partial.path("kind").asText()).isEqualTo("WRITTEN_CONFIRMATION");
      assertThat(partial.at("/gatedDraft/decision/pass").asBoolean()).isTrue();
      assertThat(post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", 1), "partial", token)).isEqualTo(partial);
      assertThatThrownBy(() -> post(client, path, Map.of("quoteRequestId", quoteId), "partial", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);
      post(client, path + "/" + partial.path("id").asText() + "/approval", approval(partial), "confirm", token);
      assertThat(get(client, path, token).path("agreedPrice").isNull()).isTrue();
      assertThat(provider.sent()).hasSize(2);
      String relayAddress = get(client, base + "/relay", token).path("address").asText();
      var received = app.context().getBean(RelayService.class).receive(new DealerMailRelay.InboundMail("written-quote", relayAddress,
          "sales@dealer.example", "Itemized quote", "Selling price $25,000 and OTD $26,800.\n" + "Itemized source line\n".repeat(56), List.of()));
      String documentId = jdbc.queryForObject("select document_id from dealer_relay_attachments where message_id = ?", String.class, received.messageId());
      String operation = jdbc.queryForObject("select id from document_processing_operations where document_id = ?", String.class, documentId);
      awaitExtraction(app, operation, token);
      var extraction = get(client, base + "/documents/" + documentId + "/extractions/latest", token);
      String corrections = base + "/documents/" + documentId + "/extractions/" + extraction.path("id").asText() + "/corrections";
      post(client, corrections, Map.of("targetType", "CANDIDATE", "targetId", find(extraction.path("candidates"), "field", "AMOUNT_FINANCED").path("id").asText(),
          "normalizedValue", "2680000", "normalizedUnit", "USD_MINOR"), "finance", token);
      var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
      extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
      extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
      var verify = Map.of("extractionId", extraction.path("id").asText(), "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines);
      post(client, base + "/dealer-offers", verify, "partial-written", token);
      var unresolved = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", 2), "unresolved", token);
      assertThat(unresolved.path("kind").asText()).isEqualTo("WRITTEN_CONFIRMATION");
      post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
          "category", "DEALER_FEE", "amountMinor", 89900), "fee", token);
      var offer = post(client, base + "/dealer-offers", verify, "itemized", token);
      int version = offer.path("version").asInt();
      assertThatThrownBy(() -> post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", 2), "stale", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", version), "no-current-plan", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      post(client, base + "/cash-economics", Map.of(), "cash-current", token);
      post(client, base + "/market-assessments", Map.of(), "market-current", token);
      post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 2000000), "plan-low", token);
      approveMandate(client, base, token, "mandate2");
      var overLimit = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", version), "over-limit", token);
      assertThat(overLimit.at("/gatedDraft/decision/pass").asBoolean()).isFalse();
      assertThatThrownBy(() -> post(client, path + "/" + overLimit.path("id").asText() + "/approval", approval(overLimit), "over-limit-send", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThat(provider.sent()).hasSize(2);
      post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 3000000), "plan-current", token);
      approveMandate(client, base, token, "mandate3");
      var prepared = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", version), "accept1", token);
      assertThat(prepared.path("kind").asText()).isEqualTo("ACCEPTANCE");
      assertThat(prepared.at("/gatedDraft/decision/pass").asBoolean()).isTrue();
      assertThat(prepared.path("evidenceMessageId").asText()).isEqualTo(received.messageId());
      var context = app.context().getBean(com.dealanalyzer.outbound.OutboundDraftService.class).context(caseId, dealer);
      String quoteQuestion = com.dealanalyzer.quote.QuoteRequestText.render("1HGCM82633A004352", "https://dealer.example/vehicle/42");
      assertThat(context.quoteRequestQuestion()).isEqualTo(quoteQuestion);
      assertThat(context.caseQuestions()).contains(quoteQuestion, com.dealanalyzer.quote.WrittenQuoteEvidence.CONFIRMATION,
          prepared.at("/gatedDraft/draft/text").asText());
      var anotherQuote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "another-quote", token);
      assertThat(anotherQuote.at("/gatedDraft/draft/text").asText()).isEqualTo(quoteQuestion);
      String approvalPath = path + "/" + prepared.path("id").asText() + "/approval";
      assertThatThrownBy(() -> post(client, approvalPath, Map.of("approved", true, "text", "The Buyer will buy"), "tampered", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      assertThatThrownBy(() -> post(client, approvalPath, Map.of("approved", false, "text", prepared.at("/gatedDraft/draft/text").asText()), "no", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      post(client, base + "/mandates/3/revocation", Map.of(), "revoke", token);
      assertThatThrownBy(() -> post(client, approvalPath, approval(prepared), "revoked", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      approveMandate(client, base, token, "mandate4");
      assertThatThrownBy(() -> post(client, approvalPath, approval(prepared), "old-mandate", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      var failed = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", version), "failed-acceptance", token);
      String failedPath = path + "/" + failed.path("id").asText() + "/approval";
      provider.failing(true);
      assertThat(post(client, failedPath, approval(failed), "failed-send", token).path("state").asText()).isEqualTo("FAILED");
      provider.failing(false);
      assertThat(post(client, failedPath, approval(failed), "failed-repeat", token).path("state").asText()).isEqualTo("FAILED");
      assertThat(provider.sent()).hasSize(2);
      assertThat(get(client, path, token).path("agreedPrice").isNull()).isTrue();
      assertThat(jdbc.queryForObject("select count(*) from price_plan_snapshots where case_id = ? and kind = 'BASELINE'", Integer.class, caseId)).isZero();
      var fresh = post(client, path, Map.of("quoteRequestId", quoteId, "offerVersion", version), "accept2", token);
      String freshPath = path + "/" + fresh.path("id").asText() + "/approval";
      var sent = post(client, freshPath, approval(fresh), "accept-send", token);
      assertThat(sent.path("state").asText()).isEqualTo("SENT");
      assertThat(post(client, freshPath, approval(fresh), "repeat", token)).isEqualTo(sent);
      assertThat(provider.sent()).hasSize(3);
      String receivedDate = jdbc.queryForObject("select created_at from dealer_relay_messages where id = ?", java.sql.Timestamp.class, received.messageId())
          .toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
      assertThat(provider.sent().getLast().body()).contains("your itemized quote for VIN 1HGCM82633A004352", "out-the-door total of USD 26800.00",
          "received on " + receivedDate + " (UTC)", "subject to a test drive", "final paperwork matching")
          .doesNotContain(received.messageId(), documentId, offer.path("id").asText(), "verified offer version", "will buy", "deposit", "commit to");
      var agreed = get(client, path, token).path("agreedPrice");
      assertThat(agreed.path("offerVersion").asInt()).isEqualTo(version);
      assertThat(agreed.path("actor").asText()).isEqualTo("BUYER");
      assertThat(agreed.path("acceptedAt").asText()).isNotBlank();
      assertThat(agreed.path("evidenceDocumentId").asText()).isEqualTo(documentId);
      assertThat(agreed.path("evidenceMessageId").asText()).isEqualTo(received.messageId());
      assertThat(agreed.at("/journey/journeyLinked").asBoolean()).isFalse();
      assertThat(agreed.at("/journey/otherOpenNegotiations")).isEmpty();
      assertThatThrownBy(() -> post(client, path + "/other-negotiations/another-case", Map.of("action", "STOP"), "stop-unlinked", token))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      var baseline = get(client, base + "/approved-baselines/" + agreed.path("baselineVersion").asInt(), token);
      assertThat(baseline.at("/payload/dealerOffer/id").asText()).isEqualTo(offer.path("id").asText());
      assertThat(jdbc.queryForObject("select count(*) from audit_events where case_id = ? and event_type = 'AGREED_PRICE_ACCEPTED'", Integer.class, caseId)).isEqualTo(1);
      jdbc.update("update negotiation_mandates set approved_at = current_timestamp - interval '1' day, expires_at = current_timestamp - interval '1' second where case_id = ?", caseId);
      assertThat(get(client, base + "/quote-requests", token).path("negotiationState").asText()).isEqualTo("AGREED");
      String summary = client.get().uri(path + "/summary").header("Case-Access-Token", token).retrieve().body(String.class);
      if (System.getProperty("agreed.price.summary.path") != null)
        java.nio.file.Files.writeString(Path.of(System.getProperty("agreed.price.summary.path")), summary);
      assertThat(summary).contains("USD 25000.00", "USD 26800.00", "USD 899.00", "USD 777.00", "USD 124.00", documentId, received.messageId(), "@page{size:letter")
          .doesNotContain(token, "Sample Buyer");
      assertThatThrownBy(() -> client.get().uri(path + "/summary").header("Case-Access-Token", "wrong").retrieve().body(String.class))
          .isInstanceOf(HttpClientErrorException.class);
      var exported = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] bytes = client.get().uri(base + "/exports/" + exported.path("id").asText()).header("Case-Access-Token", token).retrieve().body(byte[].class);
      assertThat(new String(zipBytes(bytes, "case-data.json"), java.nio.charset.StandardCharsets.UTF_8)).contains("agreed_prices", agreed.path("id").asText(), "quote_acceptance_requests");
      post(client, "/api/v1/account/sign-in-requests", Map.of("email", "agreed-buyer@example.com"), "sign-in");
      String secret = BuyerAccountAcceptanceTest.sent.getLast().link().split("#sign-in=")[1];
      String session = post(client, "/api/v1/account/sign-in/verify", Map.of("token", secret)).path("sessionToken").asText();
      client.post().uri("/api/v1/account/cases").header("Account-Session-Token", session).header("Case-Access-Token", token)
          .body(Map.of("caseId", caseId)).retrieve().toBodilessEntity();
      byte[] accountZip = client.post().uri("/api/v1/account/export").header("Account-Session-Token", session).retrieve().body(byte[].class);
      assertThat(new String(zipBytes(zipBytes(accountZip, "cases/buying-case-" + caseId + ".zip"), "case-data.json"), java.nio.charset.StandardCharsets.UTF_8))
          .contains(agreed.path("id").asText(), "agreed_prices");
      client.post().uri("/api/v1/account/deletion").header("Account-Session-Token", session).retrieve().toBodilessEntity();
      assertThatThrownBy(() -> get(client, path, token)).isInstanceOf(HttpClientErrorException.NotFound.class);
      app.context().getBean(com.dealanalyzer.account.AccountDeletionWorker.class).runOnce();
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      app.context().getBean(com.dealanalyzer.account.AccountDeletionWorker.class).runOnce();
      for (String table : List.of("agreed_prices", "quote_acceptance_requests", "quote_requests"))
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where case_id = ?", Integer.class, caseId)).isZero();
    }
  }
  private static Map<String, Object> approval(JsonNode request) {
    return Map.of("approved", true, "text", request.at("/gatedDraft/draft/text").asText());
  }
  private static void approveMandate(org.springframework.web.client.RestClient client, String base, String token, String key) throws Exception {
    var proposal = get(client, base + "/mandates/proposal", token);
    post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
        "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), key, token);
  }
  private static byte[] zipBytes(byte[] bytes, String name) throws Exception {
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) if (entry.getName().equals(name)) return zip.readAllBytes();
    }
    throw new AssertionError("Missing archive entry: " + name);
  }
}
