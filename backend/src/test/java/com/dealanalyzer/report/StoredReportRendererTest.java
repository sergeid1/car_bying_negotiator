package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class StoredReportRendererTest {
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
