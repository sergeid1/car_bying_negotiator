package com.dealanalyzer.api;

import static com.dealanalyzer.api.DocumentExtractionAcceptanceTest.*;
import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** "I'm at the dealer": routing by the paperwork's VIN, the questions it cannot answer, and the first result. */
class DeskStartAcceptanceTest {
  private static final String OTHER_VIN = "1M8GDM9AXKP042788";
  /** AGREED, ADD_ON (adds a $1,495 service contract), OTHER_CAR, or BLANK (nothing could be read). */
  static volatile String paperwork = "AGREED";

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider deskStartProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        if (paperwork.equals("BLANK"))
          return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
              extracted.expectedPageCount(), List.of(), List.of(), List.of("Nothing on these pages could be read."));
        var second = new EvidenceRegion(2, .08, .16, .55, .04);
        var candidates = new ArrayList<Candidate>();
        var lines = new ArrayList<>(extracted.lineItems());
        boolean desk = !paperwork.equals("AGREED");
        for (var candidate : extracted.candidates()) {
          if (desk && candidate.field().equals("OUT_THE_DOOR_TOTAL"))
            candidate = new Candidate("OUT_THE_DOOR_TOTAL", "Total $28,295", "2829500", "USD_MINOR", candidate.evidence(), .98, true);
          if (desk && candidate.field().equals("SUBTOTAL"))
            candidate = new Candidate("SUBTOTAL", "Subtotal $27,394", "2739400", "USD_MINOR", candidate.evidence(), .98, true);
          if (paperwork.equals("OTHER_CAR") && candidate.field().equals("VEHICLE_VIN"))
            candidate = new Candidate("VEHICLE_VIN", "VIN " + OTHER_VIN, OTHER_VIN, "VIN", candidate.evidence(), .98, true);
          candidates.add(candidate);
        }
        // An itemized quote states its add-ons, even when there are none.
        candidates.add(new Candidate("ADD_ON_TOTAL", desk ? "Add-ons $1,495" : "Add-ons $0", desk ? "149500" : "0", "USD_MINOR", second, .99, true));
        if (desk) lines.add(new LineItem("Service contract", "ADD_ON", 149_500, "USD", "NONTAXABLE", "OPTIONAL", second, .97));
        return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
            extracted.expectedPageCount(), candidates, lines, extracted.documentWarnings());
      };
    }
  }

  @TempDir Path directory;

  @Test void paperworkIsRoutedByItsVinConfirmedThenComparedAndTypedNumbersWorkWhenNothingIsRead() throws Exception {
    paperwork = "AGREED";
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("deskstart", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var cityCase = new java.util.HashMap<String, Object>(validCase());
      cityCase.remove("dealerPostalCode"); cityCase.put("dealerCity", "Raleigh"); cityCase.put("dealerState", "NC");
      var created = post(client, "/api/v1/cases", cityCase, "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, start = base + "/desk-start";
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      assertThat(get(client, start, token).path("status").asText()).isEqualTo("AWAITING_PAPERWORK");

      // The agreed car's paperwork, with an added service contract, routes to the Desk Check.
      paperwork = "ADD_ON";
      long uploaded = System.nanoTime();
      String deskDocument = upload(app, base, token, "desk-1");
      var routing = get(client, start + "?documentId=" + deskDocument, token);
      assertThat(routing.path("status").asText()).isEqualTo("READ");
      assertThat(routing.path("route").asText()).isEqualTo("AGREED_CAR");
      assertThat(routing.path("vin").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(routing.path("agreedVin").asText()).isEqualTo("1HGCM82633A004352");
      assertThat(routing.path("freshCase").isNull()).isTrue();
      assertThat(routing.at("/questions/trade/ask").asText()).as("nothing on the paperwork shows a trade").isEqualTo("WHETHER");
      assertThat(routing.at("/questions/financing/paperworkShowsLoan").asBoolean()).isTrue();
      assertThat(routing.at("/questions/financing/ask").asBoolean()).isTrue();
      assertThat(routing.has("findings")).as("nothing is compared before the Buyer confirms the routing").isFalse();

      var result = result(client, start, token, Map.of("documentId", deskDocument, "route", "AGREED_CAR"));
      long firstResultMs = (System.nanoTime() - uploaded) / 1_000_000;
      System.out.println("desk-start upload-to-first-result ms (local, in-process extraction): " + firstResultMs);
      assertThat(result.path("source").asText()).isEqualTo("READ_FROM_PAPERWORK");
      assertThat(result.at("/outTheDoor/amountMinor").asLong()).isEqualTo(2_829_500);
      assertThat(result.at("/outTheDoor/agreedMinor").asLong()).isEqualTo(2_680_000);
      assertThat(result.at("/outTheDoor/differenceMinor").asLong()).isEqualTo(149_500);
      assertThat(result.at("/lead/code").asText()).isEqualTo("ADDED_CHARGE");
      assertThat(result.at("/lead/amountMinor").asLong()).isEqualTo(149_500);
      assertThat(result.at("/lead/summary").asText()).contains("Service contract", "$1,495");
      assertThat(result.at("/lead/deskQuestion").asText()).contains("Service contract").endsWith("?");

      // A recorded trade is reused, so the trade question is not asked again.
      TradeVehicleAcceptanceTest.put(client, base + "/trade", Map.of("vin", TradeVehicleAcceptanceTest.TRADE_VIN,
          "mileage", 61000, "condition", "GOOD", "moneyOwed", "NO"), token);
      var reused = get(client, start + "?documentId=" + deskDocument, token);
      assertThat(reused.at("/questions/trade/ask").asText()).isEqualTo("NONE");
      assertThat(reused.at("/questions/trade/recorded/vehicle/vin").asText()).isEqualTo(TradeVehicleAcceptanceTest.TRADE_VIN);

      // Typed numbers compare only what was typed, and an APR above the Buyer's own lender's is priced.
      var typed = result(client, start, token, Map.of("route", "AGREED_CAR", "ownAprPercent", 4.5, "typed", Map.of(
          "sellingPriceMinor", 2_550_000, "aprPercent", 7.5, "termMonths", 60, "amountFinancedMinor", 2_000_000)));
      assertThat(typed.path("source").asText()).isEqualTo("TYPED_BY_BUYER");
      assertThat(typed.at("/outTheDoor/amountMinor").isNull()).as("an absent total is never zero").isTrue();
      // The larger dollar effect leads: about $1,674 more in payments outweighs the $500 price change.
      assertThat(typed.at("/lead/code").asText()).isEqualTo("APR_ABOVE_OWN_OFFER");
      assertThat(typed.at("/lead/amountMinor").asLong()).isBetween(160_000L, 175_000L);
      assertThat(typed.path("findings").findValuesAsText("code")).containsExactly("APR_ABOVE_OWN_OFFER", "PRICE_INCREASED", "TOTAL_NOT_SHOWN");
      assertThat(find(typed.path("findings"), "code", "PRICE_INCREASED").path("amountMinor").asLong()).isEqualTo(50_000);

      // Another car's paperwork routes to a fresh case and is never compared with the Agreed Price.
      paperwork = "OTHER_CAR";
      String otherDocument = upload(app, base, token, "desk-2");
      var other = get(client, start + "?documentId=" + otherDocument, token);
      assertThat(other.path("route").asText()).isEqualTo("FRESH_CASE");
      assertThat(other.at("/freshCase/vin").asText()).isEqualTo(OTHER_VIN);
      assertThat(other.at("/freshCase/dealerLocation").asText()).isEqualTo("Raleigh, NC");
      assertThat(other.at("/freshCase/dealerPostalCode").isNull()).isTrue();
      var refused = call(client, start, token, Map.of("documentId", otherDocument, "route", "AGREED_CAR"));
      assertThat(refused.status()).isEqualTo(409);
      assertThat(refused.body().path("code").asText()).isEqualTo("DIFFERENT_VIN");

      // A Buyer with no journey and no car yet: the case's first paperwork routes to that case.
      var fresh = post(client, "/api/v1/cases", validCase(), "fresh-case");
      String freshId = fresh.at("/case/id").asText(), freshToken = fresh.path("accessToken").asText();
      String freshStart = "/api/v1/cases/" + freshId + "/desk-start";
      String freshDocument = upload(app, "/api/v1/cases/" + freshId, freshToken, "fresh-paperwork");
      var freshRouting = get(client, freshStart + "?documentId=" + freshDocument, freshToken);
      assertThat(freshRouting.path("route").asText()).isEqualTo("THIS_CASE");
      assertThat(freshRouting.path("agreedVin").isNull()).isTrue();
      assertThat(freshRouting.at("/questions/trade/ask").asText()).as("a case outside the journey holds its own trade").isEqualTo("WHETHER");
      var freshResult = result(client, freshStart, freshToken, Map.of("documentId", freshDocument, "route", "THIS_CASE"));
      assertThat(freshResult.at("/lead/code").asText()).isEqualTo("ADD_ON");
      assertThat(freshResult.at("/lead/amountMinor").asLong()).isEqualTo(149_500);
      assertThat(freshResult.at("/outTheDoor/amountMinor").asLong()).isEqualTo(2_829_500);
      assertThat(freshResult.at("/outTheDoor/agreedMinor").isNull()).isTrue();
      assertThat(call(client, freshStart, freshToken, Map.of("documentId", freshDocument, "route", "AGREED_CAR")).status())
          .isEqualTo(409);
      assertThat(call(client, freshStart, token, Map.of("route", "THIS_CASE", "typed", Map.of("sellingPriceMinor", 1))).status())
          .as("another case's credential").isEqualTo(404);

      // Nothing could be read: the routing says so, and typed four-square numbers give the first result instead.
      paperwork = "BLANK";
      String blank = upload(app, "/api/v1/cases/" + freshId, freshToken, "blank-paperwork");
      var unreadable = get(client, freshStart + "?documentId=" + blank, freshToken);
      assertThat(unreadable.path("status").asText()).isEqualTo("UNREADABLE");
      assertThat(unreadable.path("vin").isNull()).isTrue();
      var payment = result(client, freshStart, freshToken, Map.of("route", "THIS_CASE", "typed", Map.of(
          "sellingPriceMinor", 2_500_000, "tradeAllowanceMinor", 800_000, "cashDownMinor", 200_000,
          "monthlyPaymentMinor", 52_900, "termMonths", 72)));
      assertThat(payment.at("/lead/code").asText()).isEqualTo("PAYMENT_WITHOUT_TOTAL");
      assertThat(payment.at("/lead/amountMinor").isNull()).isTrue();
      assertThat(payment.at("/lead/deskQuestion").asText()).contains("out-the-door total");

      // Refusals name the field to fix and echo nothing typed.
      var unconfirmed = call(client, freshStart, freshToken, Map.of("documentId", freshDocument));
      assertThat(unconfirmed.status()).isEqualTo(400);
      assertThat(unconfirmed.body().path("field").asText()).isEqualTo("route");
      var empty = call(client, freshStart, freshToken, Map.of("route", "THIS_CASE", "typed", Map.of()));
      assertThat(empty.status()).isEqualTo(400);
      var badVin = call(client, freshStart, freshToken, Map.of("route", "THIS_CASE", "typed", Map.of("vin", "NOTAVIN12345")));
      assertThat(badVin.status()).isEqualTo(400);
      assertThat(badVin.body().path("field").asText()).isEqualTo("vin");
      assertThat(badVin.body().toString()).doesNotContain("NOTAVIN12345");

      // The routing and the first result are deterministic reads within the recalculation latency target.
      List<Long> timings = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        long begin = System.nanoTime();
        get(client, start + "?documentId=" + deskDocument, token);
        result(client, start, token, Map.of("documentId", deskDocument, "route", "AGREED_CAR"));
        timings.add((System.nanoTime() - begin) / 1_000_000);
      }
      Collections.sort(timings);
      System.out.println("desk-start routing plus first result p95 ms (local): " + timings.get(18));
      assertThat(timings.get(18)).as("routing plus first result p95 ms").isLessThan(500);
    }
  }

  private static JsonNode result(RestClient client, String start, String token, Object body) {
    var response = call(client, start, token, body);
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    return response.body();
  }

  private static TradeVehicleAcceptanceTest.Result call(RestClient client, String start, String token, Object body) {
    return TradeVehicleAcceptanceTest.call(client, "POST", start + "/result", body, token, null);
  }

  private static String upload(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = new ObjectMapper().readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    awaitExtraction(app, started.at("/operation/id").asText(), token);
    return started.at("/document/id").asText();
  }
}
