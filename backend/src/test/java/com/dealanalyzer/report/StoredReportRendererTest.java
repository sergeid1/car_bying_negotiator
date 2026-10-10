package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class StoredReportRendererTest {
  @Test void linkedTacticsNameTheTradeLineOnceWhileV1KeepsItsWorksheetSection() throws Exception {
    var json = new ObjectMapper();
    var payload = json.readTree("""
        {"analysis":{"tradeCheck":{"findings":[{"code":"NEGATIVE_EQUITY_FINANCED","summary":"Single trade cost."}]},
        "tacticFindings":[{"code":"NEGATIVE_EQUITY_FOLDED_IN","tradeFindingCode":"NEGATIVE_EQUITY_FINANCED",
        "label":"Negative equity folded in","summary":"Single trade cost.","deskQuestion":"Please show its loan effect.",
        "policyVersion":"desk-tactics.v2","questionReview":"PENDING_OPERATOR_REVIEW","effectBasis":"From trade-desk-check.v1",
        "noDollarEffectReason":null,"monthlyEffectMinor":5000,"totalEffectMinor":300000}]}}
        """);
    var renderer = new StoredReportRenderer();
    String html = new String(renderer.html(payload), StandardCharsets.UTF_8);
    String pdf;
    try (var document = Loader.loadPDF(renderer.pdf(payload))) { pdf = new PDFTextStripper().getText(document); }
    for (String output : new String[] {html, pdf}) {
      String lead = output.substring(0, output.indexOf("Complete saved payload"));
      assertThat(lead).contains("Trade-in findings", "Negative equity folded in", "PENDING_OPERATOR_REVIEW").doesNotContain("Worksheet tactics", "Monthly effect:");
      assertThat(lead.split("Single trade cost", -1)).hasSize(2);
    }
    var v1 = json.readTree("""
        {"analysis":{"tacticFindings":[{"code":"PAYMENT_PACKING","label":"Possible payment packing",
        "summary":"The payment differs.","monthlyEffectMinor":4000,"totalEffectMinor":240000,
        "deskQuestion":"Please reconcile.","questionReview":"PENDING_OPERATOR_REVIEW","policyVersion":"desk-tactics.v1",
        "effectBasis":"Worksheet terms","noDollarEffectReason":null}]}}
        """);
    assertThat(new String(renderer.html(v1), StandardCharsets.UTF_8)).contains("Worksheet tactics", "Monthly effect: $40.00", "desk-tactics.v1").doesNotContain("Trade-in findings", "desk-tactics.v2");
  }
  @Test void bothExportsDisplayFrozenMarketFreshnessAndUncertainty() throws Exception {
    var payload = new ObjectMapper().readTree("""
        {"analysis":{"marketEvidence":{"status":"UNAVAILABLE","paragraphs":[
          "Market data retrieved: August 1, 2026.",
          "Observation window: July 20 to July 30, 2026.",
          "Market evidence is unavailable: the stored snapshot is stale. No market rating is drawn."]}}}
        """);
    var renderer = new StoredReportRenderer();
    String html = new String(renderer.html(payload), StandardCharsets.UTF_8);
    try (var document = Loader.loadPDF(renderer.pdf(payload))) {
      String pdf = new PDFTextStripper().getText(document);
      for (String text : new String[] {html, pdf})
        assertThat(text).contains("Market evidence", "Market data retrieved: August 1, 2026.",
            "Observation window: July 20 to July 30, 2026.", "snapshot is stale", "No market rating is drawn")
            .doesNotContain("T12:00:00Z");
    }
  }
  @Test void bothExportsLeadWithTheSameStoredTotalFindingsAndAction() throws Exception {
    var payload = new ObjectMapper().readTree("""
        {"version":2,"analysis":{"materialAmounts":[
          {"label":"Approved limit","minorUnits":100,"currency":"USD","precision":"EXACT","pointer":"/payload/plan/limit"},
          {"label":"/result/outTheDoorTotal","minorUnits":2876543,"currency":"USD","precision":"ESTIMATED","source":"cashEconomics","pointer":"/result/outTheDoorTotal"}],
          "topReasons":["Check fees","Check market","Check terms","Extra detail"],"nextAction":"Ask for the itemized quote"},"evidence":{}}
        """);
    var renderer = new StoredReportRenderer();
    String html = new String(renderer.html(payload), StandardCharsets.UTF_8);
    String pdf;
    try (var document = Loader.loadPDF(renderer.pdf(payload))) { pdf = new PDFTextStripper().getText(document); }
    for (var text : new String[]{html, pdf}) {
      assertThat(text).contains("28765.43 USD (ESTIMATED)", "cashEconomics /result/outTheDoorTotal");
      assertThat(text.indexOf("Out-the-door total")).isLessThan(text.indexOf("Three material findings"));
      assertThat(text.indexOf("Check terms")).isLessThan(text.indexOf("Next action"));
      assertThat(text.indexOf("Ask for the itemized quote")).isLessThan(text.indexOf("Material amounts"));
      assertThat(text.substring(0, text.indexOf("Next action"))).doesNotContain("Extra detail");
    }
  }
  @Test void unavailableAmountsStayUnknownEvenWhenAnOldNumericValueExists() throws Exception {
    var payload = new ObjectMapper().readTree("""
        {"analysis":{"materialAmounts":[{"label":"Out-the-door total","minorUnits":0,"precision":"UNAVAILABLE"}]}}
        """);
    assertThat(ReportLead.value(ReportLead.total(payload))).isEqualTo("Unknown");
    var renderer = new StoredReportRenderer();
    assertThat(new String(renderer.html(payload), StandardCharsets.UTF_8)).contains("Unknown (UNAVAILABLE)").doesNotContain("0.00");
    try (var document = Loader.loadPDF(renderer.pdf(payload))) {
      assertThat(new PDFTextStripper().getText(document)).contains("Unknown (UNAVAILABLE)");
    }
  }
  @Test void financingTotalUsesRecordedInputAndPrecision() throws Exception {
    var payload = new ObjectMapper().readTree("""
        {"analysis":{"materialAmounts":[{"label":"Out-the-door total","minorUnits":3200056,"currency":"USD","precision":"EXACT","source":"financing","pointer":"/inputs/outTheDoorTotalMinor"}]}}
        """);
    var renderer = new StoredReportRenderer();
    assertThat(new String(renderer.html(payload), StandardCharsets.UTF_8)).contains("32000.56 USD (EXACT)", "financing /inputs/outTheDoorTotalMinor");
    try (var document = Loader.loadPDF(renderer.pdf(payload))) {
      assertThat(new PDFTextStripper().getText(document)).contains("32000.56 USD (EXACT)", "financing /inputs/outTheDoorTotalMinor");
    }
  }
}
