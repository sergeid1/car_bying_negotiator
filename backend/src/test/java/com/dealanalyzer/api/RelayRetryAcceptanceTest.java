package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.dealanalyzer.relay.DealerMailRelay;
import com.dealanalyzer.relay.FakeDealerMailRelay;
import com.dealanalyzer.relay.RelayService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Ticket 73: unclear relay sends are settled by a provider lookup and retried with their original key. */
class RelayRetryAcceptanceTest {
  private static final String VIN = "1HGCM82633A004352";
  @TempDir Path directory;

  @Test void quoteRequestsAndCounteroffersSettleOrRetryWithoutReachingTheDealerTwice() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("relay-retry", true,
        FixtureExtractionProvider.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var provider = (FakeDealerMailRelay) app.context().getBean(DealerMailRelay.class);
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, relay = base + "/relay";
      String dealer = dealerWithListing(app, base, caseId, token);
      approveMandate(client, base, token, "mandate");
      int before = provider.sent().size();

      // The provider accepts the quote request but its response is lost: the send is unclear, not failed.
      var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote", token);
      provider.losingResponses(true);
      var unclear = post(client, base + "/quote-requests/" + quote.path("id").asText() + "/approval", approval(quote), "quote-send", token);
      provider.losingResponses(false);
      assertThat(unclear.path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      String quoteMessage = unclear.path("messageId").asText();
      assertThat(quoteMessage).isNotBlank();
      assertThat(provider.sent()).hasSize(before + 1);
      assertThat(get(client, base + "/quote-requests", token).path("negotiationState").asText()).isEqualTo("MANDATE_ACTIVE");
      var summary = message(get(client, relay, token), quoteMessage);
      assertThat(summary.path("deliveryCheckOffered").asBoolean()).isTrue();
      assertThat(summary.path("retryOffered").asBoolean()).isFalse();
      assertThatThrownBy(() -> post(client, relay + "/messages/" + quoteMessage + "/delivery-check", Map.of(), "x", "wrong"))
          .isInstanceOf(HttpClientErrorException.class);

      // A delivery check settles it from the provider's record and never sends.
      assertThat(post(client, relay + "/messages/" + quoteMessage + "/delivery-check", Map.of(), "check", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(post(client, relay + "/messages/" + quoteMessage + "/delivery-check", Map.of(), "check-again", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(provider.sent()).hasSize(before + 1);
      var quotes = get(client, base + "/quote-requests", token);
      assertThat(quotes.at("/requests/0/state").asText()).isEqualTo("SENT");
      assertThat(quotes.path("negotiationState").asText()).isEqualTo("AWAITING_DEALER");
      assertThat(message(get(client, relay, token), quoteMessage).path("deliveryCheckOffered").asBoolean()).isFalse();
      assertThatThrownBy(() -> post(client, relay + "/messages/" + quoteMessage + "/retry", Map.of(), "retry-sent", token))
          .isInstanceOf(HttpClientErrorException.Conflict.class);

      // A counteroffer the relay refused can be retried; an unclear retry is settled by a lookup, not a resend.
      String path = base + "/negotiation-conversation";
      post(client, base + "/analyses", Map.of(), "analysis", token);
      var draft = post(client, path + "/drafts", Map.of("quoteRequestId", quote.path("id").asText()), "draft", token);
      provider.failing(true);
      var refused = post(client, path + "/drafts/" + draft.path("id").asText() + "/approval",
          Map.of("approved", true, "text", draft.at("/revisions/0/text").asText()), "draft-send", token);
      assertThat(refused.path("state").asText()).isEqualTo("FAILED");
      String counterMessage = refused.path("messageId").asText();
      assertThat(provider.sent()).hasSize(before + 1);
      var conversation = get(client, path, token);
      var entry = conversationMessage(conversation, counterMessage);
      assertThat(entry.path("deliveryState").asText()).isEqualTo("FAILED");
      assertThat(entry.path("retryOffered").asBoolean()).isTrue();
      // While the provider cannot answer a lookup, nothing is resent.
      assertRefused(() -> post(client, relay + "/messages/" + counterMessage + "/retry", Map.of(), "retry-down", token), "CHECK_UNAVAILABLE");
      provider.failing(false);
      provider.losingResponses(true);
      assertThat(post(client, relay + "/messages/" + counterMessage + "/retry", Map.of(), "retry-lost", token).path("state").asText())
          .isEqualTo("DELIVERY_UNKNOWN");
      provider.losingResponses(false);
      assertThat(provider.sent()).hasSize(before + 2);
      assertThat(draftState(get(client, path, token), draft)).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(conversationMessage(get(client, path, token), counterMessage).path("deliveryCheckOffered").asBoolean()).isTrue();
      assertThat(post(client, relay + "/messages/" + counterMessage + "/retry", Map.of(), "retry-settles", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(provider.sent()).hasSize(before + 2);
      conversation = get(client, path, token);
      assertThat(draftState(conversation, draft)).isEqualTo("SENT");
      assertThat(conversation.path("negotiationState").asText()).isEqualTo("AWAITING_DEALER");
      // The dealer received the stored text once, exactly as approved, with the original footer.
      assertThat(provider.sent().getLast().body()).startsWith(draft.at("/revisions/0/text").asText()).contains("on behalf of a car Buyer");

      // Refusals: dealer opt-out, daily caps and a revoked mandate all stop a retry before the provider.
      var second = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote-2", token);
      provider.failing(true);
      String secondMessage = post(client, base + "/quote-requests/" + second.path("id").asText() + "/approval", approval(second), "quote-2-send", token)
          .path("messageId").asText();
      provider.failing(false);
      jdbc.update("update dealer_relay_identities set dealer_opted_out_at = current_timestamp, opt_out_channel = 'LINK' where case_id = ?", caseId);
      assertThat(message(get(client, relay, token), secondMessage).path("retryOffered").asBoolean()).isFalse();
      assertRefused(() -> post(client, relay + "/messages/" + secondMessage + "/retry", Map.of(), "opted-out", token), "OPTED_OUT");
      jdbc.update("update dealer_relay_identities set dealer_opted_out_at = null, opt_out_channel = null where case_id = ?", caseId);
      assertThat(message(get(client, relay, token), secondMessage).path("retryOffered").asBoolean()).isTrue();
      for (int i = 0; i < 10; i++)
        jdbc.update("insert into dealer_relay_send_attempts (id, case_id, dealer_address_hash, message_id, outcome, created_at) values (?, ?, 'filler', null, 'ACCEPTED', current_timestamp)",
            UUID.randomUUID().toString(), caseId);
      assertRefused(() -> post(client, relay + "/messages/" + secondMessage + "/retry", Map.of(), "capped", token), "CASE_CAP");
      jdbc.update("delete from dealer_relay_send_attempts where case_id = ? and dealer_address_hash = 'filler'", caseId);
      post(client, base + "/mandates/1/revocation", Map.of(), "revoke", token);
      assertRefused(() -> post(client, relay + "/messages/" + secondMessage + "/retry", Map.of(), "revoked", token), "NOT_PERMITTED");
      assertThat(provider.sent()).hasSize(before + 2);
      assertThat(get(client, base + "/quote-requests", token).at("/requests/1/state").asText()).isEqualTo("FAILED");

      // Every check and retry is audited without content; retries count against the daily caps.
      var outcomes = jdbc.queryForList("select event_type || ':' || metadata_json from audit_events where case_id = ? and aggregate_type = 'RELAY_MESSAGE' order by created_at, id",
          String.class, caseId);
      assertThat(outcomes).contains("RELAY_DELIVERY_CHECKED:{\"outcome\":\"SENT\"}", "RELAY_RETRY:{\"outcome\":\"CHECK_UNAVAILABLE\"}",
          "RELAY_RETRY:{\"outcome\":\"DELIVERY_UNKNOWN\"}", "RELAY_RETRY:{\"outcome\":\"SENT\"}", "RELAY_RETRY:{\"outcome\":\"OPTED_OUT\"}",
          "RELAY_RETRY:{\"outcome\":\"CASE_CAP\"}", "RELAY_RETRY:{\"outcome\":\"NOT_PERMITTED\"}", "RELAY_RETRY:{\"outcome\":\"NOT_RETRYABLE\"}");
      assertThat(String.join(" ", outcomes)).doesNotContain("dealer.example", "@", "Revised");
      assertThat(jdbc.queryForObject("select count(*) from dealer_relay_send_attempts where case_id = ? and message_id = ?",
          Integer.class, caseId, counterMessage)).isEqualTo(2);

      // The audit trail is exported and erased with the case.
      var exported = post(client, base + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] bytes = client.get().uri(base + "/exports/" + exported.path("id").asText()).header("Case-Access-Token", token).retrieve().body(byte[].class);
      assertThat(zipEntry(bytes, "case-data.json")).contains("RELAY_RETRY", "RELAY_DELIVERY_CHECKED");
      post(client, base + "/deletion", Map.of(), "delete", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      for (String table : List.of("audit_events", "dealer_relay_messages", "dealer_relay_send_attempts", "quote_requests", "negotiation_drafts"))
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where case_id = ?", Integer.class, caseId)).as(table).isZero();
    }
  }

  @Test void anUnclearAcceptanceAndTestDriveMessageReachTheirFirstSendStateOnce() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("relay-retry-agreed", true,
        AgreedPriceAcceptanceTest.Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var provider = (FakeDealerMailRelay) app.context().getBean(DealerMailRelay.class);
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, relay = base + "/relay", path = base + "/quote-acceptance";
      var prepared = prepareAcceptance(app, client, base, caseId, token);
      int before = provider.sent().size();

      provider.losingResponses(true);
      var unclear = post(client, path + "/" + prepared.path("id").asText() + "/approval", approval(prepared), "accept-send", token);
      provider.losingResponses(false);
      assertThat(unclear.path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(get(client, path, token).path("agreedPrice").isNull()).isTrue();
      String acceptanceMessage = unclear.path("messageId").asText();
      // A retry asks the provider first: it already has the message, so nothing is sent again.
      assertThat(post(client, relay + "/messages/" + acceptanceMessage + "/retry", Map.of(), "retry", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(provider.sent()).hasSize(before + 1);
      var agreed = get(client, path, token);
      assertThat(agreed.at("/requests/0/state").asText()).isEqualTo("SENT");
      assertThat(agreed.path("agreedPrice").isNull()).isFalse();
      assertThat(get(client, base + "/quote-requests", token).path("negotiationState").asText()).isEqualTo("AGREED");
      assertThat(post(client, relay + "/messages/" + acceptanceMessage + "/delivery-check", Map.of(), "check", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(jdbc.queryForObject("select count(*) from agreed_prices where case_id = ?", Integer.class, caseId)).isEqualTo(1);

      // A test-drive proposal the relay refused is retried with its stored text.
      var zone = java.time.ZoneId.of("America/Chicago");
      var day = java.time.LocalDateTime.now(zone).plusDays(3).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
      var windows = List.of(Map.of("start", day.withHour(10).toString(), "end", day.withHour(12).toString()));
      var proposal = post(client, base + "/appointment/proposals", Map.of("timeZone", "America/Chicago", "windows", windows), "propose", token);
      provider.failing(true);
      var failed = post(client, base + "/appointment/messages/" + proposal.path("id").asText() + "/approval", approval(proposal), "propose-send", token);
      provider.failing(false);
      assertThat(failed.path("state").asText()).isEqualTo("FAILED");
      String proposalMessage = failed.path("messageId").asText();
      assertThat(message(get(client, relay, token), proposalMessage).path("retryOffered").asBoolean()).isTrue();
      assertThat(post(client, relay + "/messages/" + proposalMessage + "/retry", Map.of(), "retry-proposal", token).path("state").asText())
          .isEqualTo("SENT");
      assertThat(provider.sent()).hasSize(before + 2);
      assertThat(provider.sent().getLast().body()).startsWith(proposal.at("/gatedDraft/draft/text").asText());
      var messages = get(client, base + "/appointment", token).path("messages");
      var sentProposal = find(messages, "id", proposal.path("id").asText());
      assertThat(sentProposal.path("state").asText()).isEqualTo("SENT");
      assertThat(sentProposal.path("sentAt").isNull()).isFalse();
      assertRefused(() -> post(client, relay + "/messages/" + proposalMessage + "/retry", Map.of(), "retry-again", token), "NOT_RETRYABLE");
      assertThat(provider.sent()).hasSize(before + 2);
    }
  }

  /** The ticket 63 path up to a prepared, unsent acceptance of an itemized written quote. */
  private static JsonNode prepareAcceptance(RunningApplication app, RestClient client, String base, String caseId, String token) throws Exception {
    var jdbc = app.context().getBean(JdbcTemplate.class);
    String dealer = dealerWithListing(app, base, caseId, token);
    approveMandate(client, base, token, "mandate1");
    var quote = post(client, base + "/quote-requests", Map.of("dealerId", dealer, "dealerAddress", "sales@dealer.example"), "quote", token);
    post(client, base + "/quote-requests/" + quote.path("id").asText() + "/approval", approval(quote), "send", token);
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
    post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), "fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    var offer = post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), "itemized", token);
    post(client, base + "/cash-economics", Map.of(), "cash-current", token);
    post(client, base + "/market-assessments", Map.of(), "market-current", token);
    post(client, base + "/price-plans", Map.of("maxSellingPriceMinor", 3000000), "plan-current", token);
    approveMandate(client, base, token, "mandate2");
    var prepared = post(client, base + "/quote-acceptance", Map.of("quoteRequestId", quote.path("id").asText(),
        "offerVersion", offer.path("version").asInt()), "accept", token);
    assertThat(prepared.path("kind").asText()).isEqualTo("ACCEPTANCE");
    return prepared;
  }

  private static String dealerWithListing(RunningApplication app, String base, String caseId, String token) throws Exception {
    var jdbc = app.context().getBean(JdbcTemplate.class);
    OutboundDraftAcceptanceTest.verifyAndPlan(app, base, token);
    jdbc.update("update vehicles set vin = ? where case_id = ?", VIN, caseId);
    String dealer = UUID.randomUUID().toString();
    jdbc.update("insert into dealers(id, case_id, source, provider_dealer_id, name, created_at) values (?, ?, 'TEST', 'dealer', 'Dealer', current_timestamp)", dealer, caseId);
    jdbc.update("""
        insert into listing_snapshots(id, case_id, dealer_id, provider_listing_id, listing_url, vin, source, retrieved_at, provider_version, license_metadata, created_at)
        values (?, ?, ?, 'listing', 'https://dealer.example/vehicle/42', ?, 'TEST', current_timestamp, 'v1', 'test', current_timestamp)
        """, UUID.randomUUID().toString(), caseId, dealer, VIN);
    return dealer;
  }

  private static void approveMandate(RestClient client, String base, String token, String key) throws Exception {
    var proposal = get(client, base + "/mandates/proposal", token);
    post(client, base + "/mandates", Map.of("planVersion", proposal.path("planVersion").asInt(), "targetMinor", proposal.path("targetMinor").asLong(),
        "ceilingMinor", proposal.path("ceilingMinor").asLong(), "approved", true), key, token);
  }

  private static Map<String, Object> approval(JsonNode request) {
    return Map.of("approved", true, "text", request.at("/gatedDraft/draft/text").asText());
  }

  private static JsonNode message(JsonNode relay, String id) { return find(relay.path("messages"), "id", id); }

  private static JsonNode conversationMessage(JsonNode conversation, String id) {
    for (JsonNode entry : conversation.path("entries"))
      if (id.equals(entry.at("/message/id").asText())) return entry.path("message");
    throw new AssertionError("Missing conversation message " + id);
  }

  private static String draftState(JsonNode conversation, JsonNode draft) {
    for (JsonNode entry : conversation.path("entries"))
      if (draft.path("id").asText().equals(entry.at("/counteroffer/id").asText())) return entry.at("/counteroffer/state").asText();
    throw new AssertionError("Missing counteroffer " + draft.path("id").asText());
  }

  private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
    assertThatThrownBy(call).isInstanceOfSatisfying(HttpClientErrorException.Conflict.class, conflict -> {
      try { assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(conflict.getResponseBodyAsString()).path("reason").asText()).isEqualTo(reason); }
      catch (java.io.IOException unreadable) { throw new AssertionError(unreadable); }
    });
  }

  private static String zipEntry(byte[] bytes, String name) throws Exception {
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        if (entry.getName().equals(name)) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    throw new AssertionError("Missing archive entry: " + name);
  }
}
