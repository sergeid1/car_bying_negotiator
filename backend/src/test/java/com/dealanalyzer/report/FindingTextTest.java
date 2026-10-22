package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.ai.ProhibitedClaims;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class FindingTextTest {
  private final ObjectMapper json = new ObjectMapper();
  @Test void templatesHaveStableCodesTypedAmountsAndPreservePaperworkLabels() throws Exception {
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
        {"schemaVersion":"core-deal-analysis-report.v2","version":1,"language":"es","analysis":{
          "topReasons":["Missing, stale, conflicting, or unclassified evidence prevents reliable verification."],
          "nextAction":"Resolve missing evidence, changed terms, or price-limit questions with the dealer before deciding.",
          "materialAmounts":[{"label":"DOC FEE","minorUnits":22500,"currency":"USD","precision":"EXACT",
            "source":"cashEconomics","pointer":"/result/lineItems/0/amount"}],
          "tacticFindings":[{"label":"Possible payment packing","summary":"The quoted payment is $100.00 higher per month than the worksheet's own numbers produce. It may include products not shown; ask for a reconciliation."}],
          "limitations":["Tax and fee rules are recorded estimates; unresolved classifications prevent a matching verdict."]},
          "evidence":{"label":"Purchase price"}}
        """);
    FindingText.annotate(payload, json);
    var original = payload.deepCopy();
    assertThat(payload.at("/textFindings/~1analysis~1tacticFindings~10~1summary/parameters/difference/minorUnits").asLong()).isEqualTo(10000);
    var translated = FindingText.localized(payload, "es");
    assertThat(translated.at("/analysis/topReasons/0").asText()).startsWith("La información faltante");
    assertThat(translated.at("/analysis/materialAmounts/0/label").asText()).isEqualTo("DOC FEE");
    assertThat(translated.at("/evidence/label").asText()).isEqualTo("Purchase price");
    assertThat(payload).isEqualTo(original);
    var renderer = new StoredReportRenderer();
    assertThat(new String(renderer.html(payload), StandardCharsets.UTF_8)).contains("lang=\"es\"", "hallazgos", "DOC FEE");
    try (var document = Loader.loadPDF(renderer.pdf(payload))) {
      assertThat(new PDFTextStripper().getText(document)).contains("Informe de análisis", "La información faltante", "DOC FEE");
    }
  }
  @Test void preTicketReportsAndFutureCodesRetainStoredEnglish() throws Exception {
    var old = json.readTree("""
        {"schemaVersion":"core-deal-analysis-report.v1","analysis":{"topReasons":["Old stored finding."]}}
        """);
    assertThat(FindingText.localized(old, "es")).isEqualTo(old);
    assertThat(FindingText.render("es", new FindingText.Finding("FUTURE_CODE", java.util.Map.of(), "Stored English.")))
        .isEqualTo("Stored English.");
  }
  @Test void theCrossStateTaxNoteReadsInSpanish() {
    var scope = FindingText.identify("Jurisdiction coverage: US-NC using US-NC.cash.2026.v1. "
        + com.dealanalyzer.economics.CrossStatePurchase.TAX_NOT_CHECKED);
    assertThat(scope.code()).isEqualTo("SCOPE_JURISDICTION");
    assertThat(FindingText.render("es", scope)).contains("No se comprueba el impuesto", "antes de firmar");
    assertThat(FindingText.identify(com.dealanalyzer.economics.CrossStatePurchase.TAX_NOT_CHECKED).code())
        .isEqualTo("CROSS_STATE_TAX");
    assertThat(FindingText.identify(new CoreReviewPolicy().reasons("REVIEW_REQUIRED", true, true, false).get(1)).code())
        .isEqualTo("OTD_ADDS_UP_TAX_NOT_CHECKED");
  }

  @Test void everyBackendTemplatePassesBothLanguagesClaimChecks() {
    for (var finding : FindingText.templates()) {
      assertThat(ProhibitedClaims.contains(FindingText.render("es", finding), "es")).as(finding.code()).isFalse();
      if (!java.util.Set.of("SCOPE_EXCLUSIONS", "GROUNDED_SCOPE").contains(finding.code()))
        assertThat(ProhibitedClaims.contains(FindingText.render("en", finding), "en")).as(finding.code()).isFalse();
      else assertThat(finding.text()).endsWith("Savings are not guaranteed.");
    }
  }

  @Test void quotedNumericLabelsRetainTheirPrintedSpelling() {
    var finding = FindingText.identify("What is the “001”, and is it optional?");
    assertThat(finding.parameters().get("label")).isEqualTo("001");
    assertThat(FindingText.render("es", finding)).contains("«001»");
  }

  @Test void firstDeskResultsAndTacticsUseSpanishWhileArithmeticPreservesPrintedLabels() throws Exception {
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
        {"lead":{"code":"PRICE_INCREASED","summary":"Selling price went from $25,000 to $26,000.",
           "deskQuestion":"We agreed a selling price of $25,000. Why does this say $26,000?"},
         "findings":[{"code":"PRICE_INCREASED","summary":"Selling price went from $25,000 to $26,000.",
           "deskQuestion":"We agreed a selling price of $25,000. Why does this say $26,000?"}],
         "tacticFindings":[{"label":"Possible payment packing"}]}
        """);
    FindingText.annotateDesk(payload, json);
    var translated = FindingText.localized(payload, "es");
    assertThat(translated.at("/lead/summary").asText()).startsWith("El precio de venta");
    assertThat(translated.at("/findings/0/deskQuestion").asText()).startsWith("Acordamos un precio de venta");
    assertThat(translated.at("/tacticFindings/0/label").asText()).startsWith("Posibles productos");
    var report = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
        {"analysis":{"materialAmounts":[{"label":"/result/taxes","pointer":"/result/taxes",
          "calculation":{"operands":[{"label":"Taxes","paperworkLabel":true},
             {"label":"Annual percentage rate","paperworkLabel":false}],"notes":[]}}]}}
        """);
    FindingText.annotate(report, json);
    var localized = FindingText.localized(report, "es");
    assertThat(localized.at("/analysis/materialAmounts/0/label").asText()).isEqualTo("Impuestos");
    assertThat(localized.at("/analysis/materialAmounts/0/calculation/operands/0/label").asText()).isEqualTo("Taxes");
    assertThat(localized.at("/analysis/materialAmounts/0/calculation/operands/1/label").asText()).startsWith("Tasa de porcentaje anual");
  }
}
