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

/**
 * Ticket 103: the trade allowance and payoff on desk paperwork, checked in the Desk Check, the "I'm at the
 * dealer" first result and a fresh desk case's report (acceptance scenarios 28 and 30), with labelled
 * synthetic paperwork, trade and offer.
 */
class TradeDeskCheckAcceptanceTest {
  private static final String TRADE_VIN = TradeVehicleAcceptanceTest.TRADE_VIN;
  private static final String OTHER_TRADE_VIN = TradeVehicleAcceptanceTest.OTHER_VIN;
  /** AGREED (the accepted quote), TRADE (desk paperwork with an $11,500 allowance and a $19,200 payoff) or OTHER_TRADE. */
  static volatile String paperwork = "AGREED";

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Import(BuyerAccountAcceptanceTest.EmailConfiguration.class)
  static class Configuration {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    DealTermsExtractionProvider tradeDeskProvider() {
      var fixture = new FixtureExtractionProvider().fixtureDealTermsExtractionProvider();
      return document -> {
        var extracted = fixture.extract(document);
        var second = new EvidenceRegion(2, .08, .30, .55, .04);
        var candidates = new ArrayList<Candidate>();
        // An itemized quote states its add-ons, even when there are none.
        candidates.add(new Candidate("ADD_ON_TOTAL", "Add-ons $0", "0", "USD_MINOR", second, .99, true));
        if (paperwork.equals("AGREED")) {
          candidates.addAll(0, extracted.candidates());
          return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
              extracted.expectedPageCount(), candidates, extracted.lineItems(), extracted.documentWarnings());
        }
        for (var candidate : extracted.candidates()) {
          // The financed amount as agreed, so only the trade differs.
          if (candidate.field().equals("AMOUNT_FINANCED"))
            candidate = new Candidate("AMOUNT_FINANCED", "Amount financed $26,800", "2680000", "USD_MINOR", candidate.evidence(), .98, true);
          if (paperwork.equals("ALLOWANCE_OFFSET") && candidate.field().equals("SELLING_PRICE"))
            candidate = new Candidate("SELLING_PRICE", "Selling price $27,000", "2700000", "USD_MINOR", candidate.evidence(), .98, true);
          candidates.add(candidate);
        }
        candidates.add(new Candidate("TRADE_ALLOWANCE", "Synthetic trade allowance", paperwork.equals("ALLOWANCE_OFFSET") ? "1600000" : "1150000", "USD_MINOR", second, .98, true));
        candidates.add(new Candidate("TRADE_PAYOFF", "Trade payoff $19,200", "1920000", "USD_MINOR", second, .98, true));
        String vin = paperwork.equals("OTHER_TRADE") ? OTHER_TRADE_VIN : TRADE_VIN;
        candidates.add(new Candidate("TRADE_VIN", "Trade VIN " + vin, vin, "VIN", second, .98, true));
        return new ProviderExtraction(extracted.extractorVersion(), extracted.modelVersion(), extracted.schemaVersion(),
            extracted.expectedPageCount(), candidates, extracted.lineItems(), extracted.documentWarnings());
      };
    }
  }

  @TempDir Path directory;

  @Test void deskPaperworkLeadsTheTradeFindingsWithTheShortfallFlagsThePayoffAndReachesAFreshCaseReport() throws Exception {
    paperwork = "AGREED";
    try (var app = new HttpAcceptanceHarness(directory).startWithConfiguration("tradedesk", true,
        Configuration.class, "--case.deletion.delay=PT1H",
        "--document.storage.path=" + directory.resolve("objects"),
        "--document.storage.encryption-key=0123456789abcdef0123456789abcdef")) {
      var client = app.client();
      var created = post(client, "/api/v1/cases", validCase(), "case");
      String caseId = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      String base = "/api/v1/cases/" + caseId, desk = base + "/desk-check";
      TestDriveAcceptanceTest.agree(app, client, base, caseId, token);
      recordTrade(client, caseId, token, "agreed");

      // Scenarios 28 and 30: an $11,500 allowance against a written $14,000 offer, and a $19,200 payoff
      // against the Buyer's $18,000 good through today.
      paperwork = "TRADE";
      String document = upload(app, base, token, "desk-trade");
      var view = get(client, desk, token);
      assertThat(view.path("status").asText()).isEqualTo("DIFFERENCES");
      var lead = view.at("/findings/0");
      assertThat(lead.path("group").asText()).isEqualTo("TRADE");
      assertThat(lead.path("term").asText()).isEqualTo("trade:BELOW_WRITTEN_OFFER");
      assertThat(lead.path("delta").asLong()).isEqualTo(250_000);
      assertThat(lead.path("verified").asBoolean()).as("terms as read").isFalse();
      var trade = view.path("trade");
      assertThat(trade.at("/findings/0/code").asText()).as("leads the trade findings").isEqualTo("BELOW_WRITTEN_OFFER");
      assertThat(trade.path("findings").findValuesAsText("code")).contains("TRADE_VIN_SAME", "BELOW_VALUATION_RANGE");
      var shortfall = find(trade.path("findings"), "code", "BELOW_WRITTEN_OFFER");
      assertThat(shortfall.path("summary").asText()).contains("$2,500 below", "Synthetic Car Exchange", "$14,000");
      assertThat(trade.path("shortfallToOfferMinor").asLong()).isEqualTo(250_000);
      assertThat(trade.path("payoffDifferenceMinor").asLong()).isEqualTo(120_000);
      assertThat(find(trade.path("findings"), "code", "PAYOFF_DIFFERS").path("summary").asText()).contains("$1,200 more");
      assertThat(trade.path("equityMinor").asLong()).isEqualTo(1_150_000 - 1_920_000);
      assertThat(trade.at("/taxCredit/jurisdiction").asText()).isEqualTo("US-NC");
      assertThat(trade.at("/taxCredit/creditMinor").asLong()).as("NC pack, 3% of the allowance").isEqualTo(34_500);
      assertThat(trade.at("/valuation/source").asText()).isEqualTo("SYNTHETIC_TRADE_VALUATION");
      // The trade group sits ahead of totals, and the trade never reduces the price findings (DEAL-09).
      List<String> groups = new ArrayList<>();
      view.path("findings").forEach(finding -> groups.add(finding.path("group").asText()));
      assertThat(groups.indexOf("TRADE")).isLessThan(groups.contains("TOTAL") ? groups.indexOf("TOTAL") : Integer.MAX_VALUE);
      assertThat(groups).doesNotContain("PRICE");
      assertThat(view.at("/totalEffect/outTheDoorDeltaMinor").asLong()).isZero();
      var negativeTactic = find(view.path("tacticFindings"), "code", "NEGATIVE_EQUITY_FOLDED_IN");
      assertThat(negativeTactic.path("tradeFindingCode").asText()).isEqualTo("NEGATIVE_EQUITY_FINANCED");
      assertThat(negativeTactic.path("questionReview").asText()).isEqualTo("PENDING_OPERATOR_REVIEW");
      assertThat(negativeTactic.path("totalEffectMinor").asLong()).isEqualTo("AVAILABLE".equals(trade.at("/negativeEquityCost/status").asText())
          ? trade.at("/negativeEquityCost/scheduledPaymentsIncreaseMinor").asLong() : trade.path("negativeEquityFinancedMinor").asLong());

      // The first result from "I'm at the dealer" carries the same trade findings.
      var result = TradeVehicleAcceptanceTest.call(client, "POST", base + "/desk-start/result",
          Map.of("documentId", document, "route", "AGREED_CAR"), token, null);
      assertThat(result.status()).isEqualTo(200);
      assertThat(result.body().path("findings").findValuesAsText("code")).contains("BELOW_WRITTEN_OFFER", "PAYOFF_DIFFERS");
      assertThat(result.body().at("/trade/shortfallToOfferMinor").asLong()).isEqualTo(250_000);
      assertThat(result.body().path("findings").findValuesAsText("code")).doesNotContain("NEGATIVE_EQUITY_FOLDED_IN");
      assertThat(result.body().path("findings").findValuesAsText("code").stream().filter("NEGATIVE_EQUITY_FINANCED"::equals).count()).isEqualTo(1);
      assertThat(find(result.body().path("findings"), "code", "NEGATIVE_EQUITY_FINANCED").path("tacticCode").asText()).isEqualTo("NEGATIVE_EQUITY_FOLDED_IN");

      paperwork = "ALLOWANCE_OFFSET";
      String offsetDocument = upload(app, base, token, "desk-allowance-offset");
      var offsetView = get(client, desk, token);
      var allowanceTactic = find(offsetView.path("tacticFindings"), "code", "ALLOWANCE_PAID_FOR_BY_PRICE");
      assertThat(allowanceTactic.path("tradeFindingCode").asText()).isEqualTo("ALLOWANCE_OFFSET_BY_PRICE");
      assertThat(allowanceTactic.path("totalEffectMinor")).isEqualTo(offsetView.at("/trade/priceTogether/netEffectMinor"));
      var offsetResult = TradeVehicleAcceptanceTest.call(client, "POST", base + "/desk-start/result",
          Map.of("documentId", offsetDocument, "route", "AGREED_CAR"), token, null).body();
      assertThat(offsetResult.path("findings").findValuesAsText("code")).doesNotContain("ALLOWANCE_PAID_FOR_BY_PRICE");
      assertThat(offsetResult.path("findings").findValuesAsText("code").stream().filter("ALLOWANCE_OFFSET_BY_PRICE"::equals).count()).isEqualTo(1);
      assertThat(find(offsetResult.path("findings"), "code", "ALLOWANCE_OFFSET_BY_PRICE").path("tacticCode").asText()).isEqualTo("ALLOWANCE_PAID_FOR_BY_PRICE");

      // A different trade-in VIN is one blocking finding; nothing about the trade is compared.
      paperwork = "OTHER_TRADE";
      upload(app, base, token, "desk-other-trade");
      var other = get(client, desk, token);
      var blocking = find(other.path("findings"), "term", "trade:TRADE_VIN_DIFFERENT");
      assertThat(blocking.path("severity").asText()).isEqualTo("BLOCKING");
      assertThat(other.at("/trade/findings").findValuesAsText("code")).containsExactly("TRADE_VIN_DIFFERENT");
      assertThat(other.at("/trade/equityMinor").isNull()).isTrue();

      // A fresh desk case without a journey holds its own trade, and its report leads with the trade.
      paperwork = "TRADE";
      var fresh = post(client, "/api/v1/cases", validCase(), "fresh-case");
      String freshId = fresh.at("/case/id").asText(), freshToken = fresh.path("accessToken").asText();
      String freshBase = "/api/v1/cases/" + freshId;
      recordTrade(client, freshId, freshToken, "fresh");
      post(client, freshBase + "/vehicle-evidence", Map.of("vin", "1HGCM82633A004352", "confirmed", true), "fresh-vehicle", freshToken);
      String freshDocument = upload(app, freshBase, freshToken, "fresh-paperwork");
      verify(client, freshBase, freshToken, freshDocument, "fresh");
      post(client, freshBase + "/cash-economics", Map.of(), "fresh-cash", freshToken);
      post(client, freshBase + "/market-assessments", Map.of(), "fresh-market", freshToken);
      post(client, freshBase + "/analyses", Map.of(), "fresh-analysis", freshToken);
      var report = get(client, freshBase + "/reports/latest", freshToken);
      var check = report.at("/analysis/tradeCheck");
      assertThat(check.path("status").asText()).isEqualTo("CHECKED");
      assertThat(check.path("shortfallToOfferMinor").asLong()).isEqualTo(250_000);
      assertThat(check.path("payoffDifferenceMinor").asLong()).isEqualTo(120_000);
      assertThat(report.at("/analysis/topReasons/0").asText()).contains("$2,500 below", "Synthetic Car Exchange");
      assertThat(find(report.at("/analysis/tacticFindings"), "code", "NEGATIVE_EQUITY_FOLDED_IN").path("tradeFindingCode").asText()).isEqualTo("NEGATIVE_EQUITY_FINANCED");
      assertThat(report.at("/analysis/tacticFindings").findValuesAsText("code")).doesNotContain("ALLOWANCE_PAID_FOR_BY_PRICE");
      assertThat(get(client, freshBase + "/desk-check", freshToken).path("status").asText()).isEqualTo("NO_AGREED_PRICE");

      // An above-value allowance on a fresh case has no agreed-price increase to name as a tactic.
      paperwork = "ALLOWANCE_OFFSET";
      String freshAboveDocument = upload(app, freshBase, freshToken, "fresh-above-value");
      verify(client, freshBase, freshToken, freshAboveDocument, "fresh-above");
      post(client, freshBase + "/cash-economics", Map.of(), "fresh-above-cash", freshToken);
      post(client, freshBase + "/analyses", Map.of(), "fresh-above-analysis", freshToken);
      var freshAboveReport = get(client, freshBase + "/reports/latest", freshToken);
      assertThat(freshAboveReport.at("/analysis/tradeCheck/findings").findValuesAsText("code")).contains("ALLOWANCE_ABOVE_VALUE");
      assertThat(freshAboveReport.at("/analysis/tradeCheck/priceTogether/priceIncreaseMinor").isNull()).isTrue();
      assertThat(freshAboveReport.at("/analysis/tacticFindings").findValuesAsText("code")).doesNotContain("ALLOWANCE_PAID_FOR_BY_PRICE");
      assertThat(get(client, freshBase + "/reports/1", freshToken)).isEqualTo(report);
    }
  }

  private static void recordTrade(RestClient client, String caseId, String token, String key) throws Exception {
    TradeVehicleAcceptanceTest.put(client, "/api/v1/cases/" + caseId + "/trade", TradeVehicleAcceptanceTest.input(null,
        "YES", "Synthetic Credit Union", 1_800_000L, TradeVehicleAcceptanceTest.today().toString(), false), token);
    var added = TradeVehicleAcceptanceTest.addOffer(client, caseId, token, key + "-offer", 1_400_000, "Synthetic Car Exchange",
        TradeVehicleAcceptanceTest.today().minusDays(1), TradeVehicleAcceptanceTest.today().plusDays(7));
    assertThat(added.status()).as(added.toString()).isEqualTo(201);
  }

  private static String upload(RunningApplication app, String base, String token, String key) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("file", new NamedBytes(key + ".pdf", twoPagePdf()));
    var started = new ObjectMapper().readTree(app.client().post().uri(base + "/documents").contentType(MediaType.MULTIPART_FORM_DATA)
        .header("Idempotency-Key", key).header("Case-Access-Token", token).body(parts).retrieve().body(String.class));
    assertThat(awaitExtraction(app, started.at("/operation/id").asText(), token).path("stage").asText()).isEqualTo("EXTRACTION_COMPLETE");
    return started.at("/document/id").asText();
  }

  private static JsonNode verify(RestClient client, String base, String token, String documentId, String key) throws Exception {
    String document = base + "/documents/" + documentId;
    var extraction = get(client, document + "/extractions/latest", token);
    String corrections = document + "/extractions/" + extraction.path("id").asText() + "/corrections";
    post(client, corrections, Map.of("targetType", "LINE_ITEM", "targetId", find(extraction.path("lineItems"), "category", "UNKNOWN").path("id").asText(),
        "category", "DEALER_FEE", "amountMinor", 89900), key + "-fee", token);
    var candidates = new ArrayList<String>(); var lines = new ArrayList<String>();
    extraction.path("candidates").forEach(value -> candidates.add(value.path("id").asText()));
    extraction.path("lineItems").forEach(value -> lines.add(value.path("id").asText()));
    return post(client, base + "/dealer-offers", Map.of("extractionId", extraction.path("id").asText(),
        "confirmedCandidateIds", candidates, "confirmedLineItemIds", lines), key + "-verified", token);
  }
}
