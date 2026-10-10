package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.NamedBytes;
import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.twoPagePdf;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.account.AccountDeletionWorker;
import com.dealanalyzer.buyingcase.persistence.CaseDeletionWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Ticket 100: the Trade Vehicle, its payoff and the Buyer's own written trade offers (TRADE-01, TRADE-02). */
class TradeVehicleAcceptanceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String TRADE_VIN = "5J6RW2H52ML000002";
  static final String OTHER_VIN = "2T3P1RFV5MW000001";
  @TempDir Path directory;

  @BeforeEach void clear() { ShortlistAcceptanceTest.links.clear(); }

  @Test void aCaseWithoutAJourneyHoldsItsOwnTradeWithPayoffRulesAndWrittenOffers() throws Exception {
    try (var app = start("case-trade")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String trade = "/api/v1/cases/" + id + "/trade";

      var empty = call(client, "GET", trade, null, token, null);
      assertThat(empty.status()).isEqualTo(200);
      assertThat(empty.body().path("holder").asText()).isEqualTo("CASE");
      assertThat(empty.body().path("trade").isNull()).isTrue();
      assertThat(empty.body().path("conditions").findValuesAsText("value")).containsExactly("EXCELLENT", "GOOD", "FAIR", "POOR");
      assertThat(empty.body().path("conditions").findValuesAsText("description")).allMatch(text -> text.length() > 20);

      // An invalid VIN is refused with ticket 52's checksum message, and nothing is saved.
      var invalid = call(client, "PUT", trade, Map.of("vin", "5J6RW2H52ML000003", "mileage", 61000, "condition", "GOOD"), token, null);
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().path("code").asText()).isEqualTo("VIN_INVALID");
      assertThat(invalid.body().at("/errors/0/message").asText())
          .isEqualTo(com.dealanalyzer.journey.vin.VinChecksum.MESSAGE).doesNotContain("5J6RW2H52ML000003");
      assertThat(call(client, "GET", trade, null, token, null).body().path("trade").isNull()).isTrue();

      // Recorded without the owed answer, the trade is decoded but not complete.
      var recorded = put(client, trade, Map.of("vin", TRADE_VIN.toLowerCase(), "mileage", 61000, "condition", "GOOD"), token);
      var vehicle = recorded.at("/trade/vehicle");
      assertThat(vehicle.path("vin").asText()).isEqualTo(TRADE_VIN);
      assertThat(List.of(vehicle.path("year").asText(), vehicle.path("make").asText(), vehicle.path("model").asText(),
          vehicle.path("trim").asText())).containsExactly("2021", "Honda", "CR-V", "EX");
      assertThat(vehicle.path("decodeOutcome").asText()).isEqualTo("DECODED");
      assertThat(recorded.at("/trade/complete").asBoolean()).isFalse();
      assertThat(recorded.at("/trade/revision").asInt()).isEqualTo(1);

      // Yes asks for lender, payoff and good-through date.
      var missing = call(client, "PUT", trade, input(1, "YES", null, null, null, false), token, null);
      assertThat(missing.status()).isEqualTo(400);
      assertThat(missing.body().path("errors").findValuesAsText("field")).containsExactly("lender", "payoffMinor", "payoffGoodThrough");
      // "I don't know" leaves the payoff unknown, never zero.
      var unknown = put(client, trade, input(1, "YES", "First Coast Credit Union", null, null, true), token);
      assertThat(unknown.at("/trade/payoffMinor").isNull()).isTrue();
      assertThat(unknown.at("/trade/payoffUnknown").asBoolean()).isTrue();
      assertThat(unknown.at("/trade/complete").asBoolean()).isTrue();
      assertThat(jdbc(app).queryForObject("select payoff_minor from trade_vehicles where case_id = ?", Long.class, id)).isNull();
      var notSure = put(client, trade, input(2, "NOT_SURE", "ignored lender", 1L, today().toString(), false), token);
      assertThat(notSure.at("/trade/lender").isNull()).isTrue();
      assertThat(notSure.at("/trade/payoffMinor").isNull()).isTrue();
      assertThat(notSure.at("/trade/payoffUnknown").asBoolean()).isTrue();

      // A payoff past its good-through date is stale; a current one is not.
      var stale = put(client, trade, input(3, "YES", "First Coast Credit Union", 1825000L, today().minusDays(1).toString(), false), token);
      assertThat(stale.at("/trade/payoffMinor").asLong()).isEqualTo(1825000);
      assertThat(stale.at("/trade/payoffStale").asBoolean()).isTrue();
      var current = put(client, trade, input(4, "YES", "First Coast Credit Union", 1825000L, today().plusDays(10).toString(), false), token);
      assertThat(current.at("/trade/payoffStale").asBoolean()).isFalse();
      int revision = current.at("/trade/revision").asInt();
      assertThat(revision).isEqualTo(5);
      // Saving the same details changes nothing; an edit of an older revision is refused.
      assertThat(put(client, trade, input(4, "YES", "First Coast Credit Union", 1825000L, today().plusDays(10).toString(), false), token)
          .at("/trade/revision").asInt()).isEqualTo(revision);
      var conflict = call(client, "PUT", trade, input(2, "NO", null, null, null, false), token, null);
      assertThat(conflict.status()).isEqualTo(409);
      assertThat(conflict.body().path("code").asText()).isEqualTo("TRADE_CHANGED");

      // Written offers: an expired one stays visible but is never used for comparison.
      var expired = addOffer(client, id, token, "expired-offer", 1500000, "Coastal Auto Buyers",
          today().minusDays(20), today().minusDays(1));
      assertThat(expired.status()).isEqualTo(201);
      var withOffers = addOffer(client, id, token, "current-offer", 1400000, "Sunshine Car Exchange",
          today().minusDays(2), today().plusDays(5)).body();
      var offers = withOffers.at("/trade/offers");
      assertThat(offers).hasSize(2);
      assertThat(offers.get(0).path("expired").asBoolean()).isTrue();
      assertThat(offers.get(0).path("usedForComparison").asBoolean()).isFalse();
      assertThat(offers.get(1).path("usedForComparison").asBoolean()).isTrue();
      assertThat(withOffers.at("/trade/bestOfferId").asText()).isEqualTo(offers.get(1).path("id").asText());
      // A retried request adds nothing.
      assertThat(addOffer(client, id, token, "current-offer", 1400000, "Sunshine Car Exchange", today().minusDays(2),
          today().plusDays(5)).body().at("/trade/offers")).hasSize(2);
      var refused = addOffer(client, id, token, "bad-dates", 1, "", today().plusDays(5), today());
      assertThat(refused.status()).isEqualTo(400);
      assertThat(refused.body().path("errors").findValuesAsText("field")).contains("source", "offerDate");

      // The evidence went through ordinary intake, is never listed as dealer paperwork, and its pages are viewable.
      String offerId = offers.get(1).path("id").asText();
      String documentId = offers.get(1).at("/evidence/documentId").asText();
      awaitEvidence(client, trade, token, offerId);
      assertThat(jdbc(app).queryForObject("select purpose from source_documents where id = ?", String.class, documentId))
          .isEqualTo("TRADE_OFFER_EVIDENCE");
      assertThat(jdbc(app).queryForObject("select count(*) from document_extractions where document_id = ?", Long.class, documentId)).isZero();
      assertThat(get(client, "/api/v1/cases/" + id + "/documents", token).findValuesAsText("id")).doesNotContain(documentId);
      byte[] page = client.get().uri(trade + "/offers/" + offerId + "/pages/1/image").header("Case-Access-Token", token)
          .retrieve().body(byte[].class);
      assertThat(page).startsWith(0x89, 'P', 'N', 'G');

      // Removing an offer brings its evidence's retention forward.
      var removed = call(client, "DELETE", trade + "/offers/" + offerId, null, token, null);
      assertThat(removed.status()).isEqualTo(200);
      assertThat(removed.body().at("/trade/offers")).hasSize(1);
      assertThat(jdbc(app).queryForObject("select retention_deadline <= current_timestamp from source_documents where id = ?",
          Boolean.class, documentId)).isTrue();
      assertThat(call(client, "DELETE", trade + "/offers/" + offerId, null, token, null).status()).isEqualTo(404);

      // Nothing about the trade is in any dealer-facing message: the case has none, and the trade is not in its drafts.
      assertThat(jdbc(app).queryForObject("select count(*) from outbound_drafts where case_id = ?", Long.class, id)).isZero();

      // The case export holds the trade and its offers; case deletion removes them.
      var job = post(client, "/api/v1/cases/" + id + "/exports", Map.of("format", "ZIP", "reportVersion", 0), "export", token);
      byte[] archive = client.get().uri("/api/v1/cases/" + id + "/exports/" + job.path("id").asText())
          .header("Case-Access-Token", token).retrieve().body(byte[].class);
      var data = JSON.readTree(zipEntries(archive).get("case-data.json"));
      assertThat(data.at("/trade_vehicles/0/vin").asText()).isEqualTo(TRADE_VIN);
      assertThat(data.at("/trade_vehicles/0/lender").asText()).isEqualTo("First Coast Credit Union");
      assertThat(data.path("trade_offers")).hasSize(1);
      post(client, "/api/v1/cases/" + id + "/deletion", Map.of(), "delete", token);
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      for (String table : List.of("trade_vehicles", "trade_offers"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
  }

  @Test void aWrittenOfferNeedsARecordedTradeAndAnInvalidOrUnavailableDecoderSavesNothing() throws Exception {
    try (var app = start("trade-preconditions")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      var refused = addOffer(client, id, token, "early", 1400000, "Sunshine Car Exchange", today(), today().plusDays(3));
      assertThat(refused.status()).isEqualTo(409);
      assertThat(refused.body().path("code").asText()).isEqualTo("TRADE_NOT_RECORDED");
      assertThat(jdbc(app).queryForObject("select count(*) from source_documents", Long.class)).isZero();
      var unavailable = call(client, "PUT", "/api/v1/cases/" + id + "/trade", Map.of("vin",
          com.dealanalyzer.journey.vin.FakeVinDecoder.UNAVAILABLE_VIN, "mileage", 10, "condition", "FAIR"), token, null);
      assertThat(unavailable.status()).isEqualTo(503);
      assertThat(unavailable.body().path("code").asText()).isEqualTo("VIN_DECODER_UNAVAILABLE");
      assertThat(jdbc(app).queryForObject("select count(*) from trade_vehicles", Long.class)).isZero();
      // Another case's credential reads nothing.
      var other = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "other");
      assertThat(call(client, "GET", "/api/v1/cases/" + id + "/trade", null, other.path("accessToken").asText(), null).status())
          .isEqualTo(404);
    }
  }

  @Test void aChangeAfterAnalysisMarksThatAnalysisOutdated() throws Exception {
    try (var app = start("trade-outdated")) {
      var client = app.client();
      JsonNode golden;
      try (var input = getClass().getResourceAsStream("/fixtures/golden/cash-deal-standard.json")) { golden = JSON.readTree(input); }
      var started = post(client, "/api/v1/cases/synthetic", golden.path("input"), "synthetic");
      String id = started.at("/case/id").asText(), token = started.path("accessToken").asText();
      awaitCompleted(client, started.at("/operation/id").asText(), token);
      String trade = "/api/v1/cases/" + id + "/trade";
      var before = call(client, "GET", trade, null, token, null).body();
      assertThat(before.at("/analysis/analyzedAt").asText()).isNotBlank();
      assertThat(before.at("/analysis/outdated").asBoolean()).isFalse();
      var changed = put(client, trade, Map.of("vin", TRADE_VIN, "mileage", 61000, "condition", "GOOD", "moneyOwed", "NO"), token);
      assertThat(changed.at("/analysis/outdated").asBoolean()).isTrue();
    }
  }

  @Test void theJourneyTradeCarriesIntoEveryCaseIncludingAFreshDeskCaseAndIsExportedAndDeletedWithTheJourney() throws Exception {
    try (var app = start("journey-trade")) {
      var client = app.client();
      String session = signIn(client, "trader@example.com");
      String journeyId = journey(client, session, "journey"), base = "/api/v1/journeys/" + journeyId;
      var candidates = account(client, "POST", base + "/candidates", null, session, null).body().path("candidates");
      var first = openCase(client, base, candidates.get(0).path("candidateId").asText(), session, "first");
      var second = openCase(client, base, candidates.get(1).path("candidateId").asText(), session, "second");
      String firstId = first.at("/case/id").asText(), firstToken = first.path("accessToken").asText();
      String secondId = second.at("/case/id").asText(), secondToken = second.path("accessToken").asText();

      var recorded = put(client, "/api/v1/cases/" + firstId + "/trade", input(null, "YES", "Gulf Bank", 1800000L,
          today().plusDays(10).toString(), false), firstToken);
      assertThat(recorded.path("holder").asText()).isEqualTo("JOURNEY");
      String tradeId = recorded.at("/trade/id").asText();
      // The second case uses the same Trade Vehicle, and an offer added through it shows in the first.
      var seen = call(client, "GET", "/api/v1/cases/" + secondId + "/trade", null, secondToken, null).body();
      assertThat(seen.at("/trade/id").asText()).isEqualTo(tradeId);
      addOffer(client, secondId, secondToken, "journey-offer", 1400000, "Sunshine Car Exchange", today(), today().plusDays(7));
      var firstView = call(client, "GET", "/api/v1/cases/" + firstId + "/trade", null, firstToken, null).body();
      assertThat(firstView.at("/trade/offers")).hasSize(1);
      String offerId = firstView.at("/trade/offers/0/id").asText();
      awaitEvidence(client, "/api/v1/cases/" + firstId + "/trade", firstToken, offerId);
      assertThat(client.get().uri("/api/v1/cases/" + firstId + "/trade/offers/" + offerId + "/pages/1/image")
          .header("Case-Access-Token", firstToken).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);

      // A fresh desk case for a different car joins the journey's trade; both credentials are required.
      var fresh = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "fresh");
      String freshId = fresh.at("/case/id").asText(), freshToken = fresh.path("accessToken").asText();
      String carry = "/api/v1/cases/" + freshId + "/trade/carry-over";
      assertThat(call(client, "POST", carry, Map.of("sourceCaseId", firstId), freshToken, null).status()).isEqualTo(404);
      assertThat(call(client, "POST", carry, Map.of("sourceCaseId", firstId), freshToken, secondToken).status()).isEqualTo(404);
      var joined = call(client, "POST", carry, Map.of("sourceCaseId", firstId), freshToken, firstToken);
      assertThat(joined.status()).isEqualTo(200);
      assertThat(joined.body().path("holder").asText()).isEqualTo("JOURNEY");
      assertThat(joined.body().at("/trade/id").asText()).isEqualTo(tradeId);
      assertThat(call(client, "POST", carry, Map.of("sourceCaseId", firstId), freshToken, firstToken).body().at("/trade/id").asText())
          .isEqualTo(tradeId);
      // An edit through the fresh case is the journey's edit.
      put(client, "/api/v1/cases/" + freshId + "/trade", Map.of("basedOnRevision", 1, "vin", TRADE_VIN, "mileage", 62000,
          "condition", "FAIR", "moneyOwed", "NO"), freshToken);
      assertThat(call(client, "GET", "/api/v1/cases/" + firstId + "/trade", null, firstToken, null).body()
          .at("/trade/mileage").asInt()).isEqualTo(62000);

      // A case without a journey carries a copy of its vehicle and payoff into its fresh desk case.
      var solo = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "solo");
      String soloId = solo.at("/case/id").asText(), soloToken = solo.path("accessToken").asText();
      put(client, "/api/v1/cases/" + soloId + "/trade", input(null, "YES", "Gulf Bank", 900000L, today().plusDays(3).toString(), false), soloToken);
      var soloFresh = post(client, "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase(), "solo-fresh");
      var copied = call(client, "POST", "/api/v1/cases/" + soloFresh.at("/case/id").asText() + "/trade/carry-over",
          Map.of("sourceCaseId", soloId), soloFresh.path("accessToken").asText(), soloToken).body();
      assertThat(copied.path("holder").asText()).isEqualTo("CASE");
      assertThat(copied.at("/trade/payoffMinor").asLong()).isEqualTo(900000);
      assertThat(copied.at("/trade/vehicle/vin").asText()).isEqualTo(TRADE_VIN);

      // The account export holds the journey's trade, payoff and offers.
      byte[] archive = client.post().uri("/api/v1/account/export").header("Cookie", AccountSessionTestSupport.cookie(session))
          .header("Account-Request", "1").retrieve().body(byte[].class);
      var exported = JSON.readTree(zipEntries(archive).get("account.json")).at("/buyingJourneys/0/tradeVehicle");
      assertThat(exported.path("vin").asText()).isEqualTo(TRADE_VIN);
      assertThat(exported.path("mileage").asInt()).isEqualTo(62000);
      assertThat(exported.path("offers")).hasSize(1);
      assertThat(exported.path("deskCheckCases").findValuesAsText("caseId")).containsExactly(freshId);

      // Journey deletion removes its trade, offers and desk case link and erases the offer evidence under retention.
      String evidence = firstView.at("/trade/offers/0/evidence/documentId").asText();
      assertThat(account(client, "DELETE", base, null, session, null).status()).isEqualTo(200);
      var jdbc = jdbc(app);
      assertThat(jdbc.queryForObject("select count(*) from trade_vehicles where id = ?", Long.class, tradeId)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from trade_offers where id = ?", Long.class, offerId)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from trade_case_journeys", Long.class)).isZero();
      assertThat(jdbc.queryForObject("select retention_deadline <= current_timestamp from source_documents where id = ?",
          Boolean.class, evidence)).isTrue();
      assertThat(call(client, "GET", "/api/v1/cases/" + freshId + "/trade", null, freshToken, null).body().path("trade").isNull()).isTrue();
    }
  }

  @Test void accountDeletionRemovesTheJourneyTrade() throws Exception {
    try (var app = start("account-trade")) {
      var client = app.client();
      String session = signIn(client, "leaving@example.com");
      String journeyId = journey(client, session, "journey"), base = "/api/v1/journeys/" + journeyId;
      var candidate = account(client, "POST", base + "/candidates", null, session, null).body().at("/candidates/0/candidateId").asText();
      var opened = openCase(client, base, candidate, session, "case");
      String caseId = opened.at("/case/id").asText(), token = opened.path("accessToken").asText();
      put(client, "/api/v1/cases/" + caseId + "/trade", input(null, "YES", "Gulf Bank", 1800000L, today().plusDays(10).toString(), false), token);
      addOffer(client, caseId, token, "offer", 1400000, "Sunshine Car Exchange", today(), today().plusDays(7));
      assertThat(account(client, "POST", "/api/v1/account/deletion", Map.of(), session, null).status()).isEqualTo(202);
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      app.context().getBean(CaseDeletionWorker.class).runOnce();
      app.context().getBean(AccountDeletionWorker.class).runOnce();
      for (String table : List.of("trade_vehicles", "trade_offers", "trade_case_journeys"))
        assertThat(jdbc(app).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
  }

  /** For the case isolation inventory: a case-held trade with one written offer; returns the offer ID. */
  static String recordTradeWithOffer(RunningApplication app, String caseId, String token, String label) throws Exception {
    var client = app.client();
    put(client, "/api/v1/cases/" + caseId + "/trade", Map.of("vin", TRADE_VIN, "mileage", 61000, "condition", "GOOD",
        "moneyOwed", "NO"), token);
    var added = addOffer(client, caseId, token, label + "-trade-offer", 1400000, "Sunshine Car Exchange", today(), today().plusDays(7));
    assertThat(added.status()).as(added.toString()).isEqualTo(201);
    return added.body().at("/trade/offers/0/id").asText();
  }

  static LocalDate today() { return LocalDate.now(ZoneId.of("America/New_York")); }

  static Map<String, Object> input(Integer revision, String owed, String lender, Long payoff, String goodThrough, boolean unknown) {
    var input = new LinkedHashMap<String, Object>();
    input.put("basedOnRevision", revision); input.put("vin", TRADE_VIN); input.put("mileage", 61000);
    input.put("condition", "GOOD"); input.put("moneyOwed", owed); input.put("lender", lender); input.put("payoffMinor", payoff);
    input.put("payoffGoodThrough", goodThrough); input.put("payoffUnknown", unknown);
    return input;
  }

  record Result(int status, JsonNode body) {}

  static JsonNode put(RestClient client, String path, Object body, String token) {
    var result = call(client, "PUT", path, body, token, null);
    assertThat(result.status()).as(result.toString()).isEqualTo(200);
    return result.body();
  }

  static Result call(RestClient client, String method, String path, Object body, String token, String sourceToken) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Case-Access-Token", token);
    if (sourceToken != null) request.header("Source-Case-Access-Token", sourceToken);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  static Result addOffer(RestClient client, String caseId, String token, String key, long amountMinor, String source,
      LocalDate offerDate, LocalDate expiresOn) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("amountMinor", Long.toString(amountMinor)); parts.add("source", source);
    parts.add("offerDate", offerDate.toString()); parts.add("expiresOn", expiresOn.toString());
    parts.add("file", new NamedBytes("offer.pdf", twoPagePdf()));
    return client.post().uri("/api/v1/cases/" + caseId + "/trade/offers").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Case-Access-Token", token).header("Idempotency-Key", key).body(parts)
        .exchange((ignored, response) -> {
          byte[] bytes = response.getBody().readAllBytes();
          return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
        });
  }

  private static void awaitEvidence(RestClient client, String trade, String token, String offerId) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    String state;
    do {
      state = "";
      for (var offer : call(client, "GET", trade, null, token, null).body().at("/trade/offers"))
        if (offer.path("id").asText().equals(offerId)) state = offer.at("/evidence/state").asText();
      if (state.equals("READY")) return;
      Thread.sleep(200);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Evidence did not finish processing: " + state);
  }

  private RunningApplication start(String database) {
    return new HttpAcceptanceHarness(directory).startWithConfiguration(database, true, ShortlistAcceptanceTest.Configuration.class,
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef", "--case.deletion.delay=PT1H");
  }

  private static JdbcTemplate jdbc(RunningApplication app) {
    return new JdbcTemplate(app.context().getBean(javax.sql.DataSource.class));
  }

  private static String signIn(RestClient client, String email) throws Exception {
    post(client, "/api/v1/account/sign-in-requests", Map.of("email", email), UUID.randomUUID().toString());
    return AccountSessionTestSupport.verify(client, ShortlistAcceptanceTest.links.getLast().split("#sign-in=")[1]);
  }

  private static String journey(RestClient client, String session, String key) {
    var result = account(client, "POST", "/api/v1/journeys", BuyingJourneyAcceptanceTest.brief(), session, key);
    assertThat(result.status()).isEqualTo(201);
    return result.body().path("id").asText();
  }

  private static JsonNode openCase(RestClient client, String base, String candidate, String session, String key) {
    var result = account(client, "POST", base + "/candidates/" + candidate + "/case", CasePortabilityAcceptanceTest.buyingCase(),
        session, key);
    assertThat(result.status()).as(result.toString()).isEqualTo(201);
    return result.body();
  }

  private static Result account(RestClient client, String method, String path, Object body, String session, String key) {
    var request = client.method(HttpMethod.valueOf(method)).uri(path).header("Cookie", AccountSessionTestSupport.cookie(session));
    if (!"GET".equals(method)) request.header("Account-Request", "1");
    if (key != null) request.header("Idempotency-Key", key);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange((ignored, response) -> {
      byte[] bytes = response.getBody().readAllBytes();
      return new Result(response.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
    });
  }

  private static Map<String, String> zipEntries(byte[] archive) throws Exception {
    var result = new HashMap<String, String>();
    try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
        if (entry.getName().endsWith(".json")) result.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    return result;
  }
}
