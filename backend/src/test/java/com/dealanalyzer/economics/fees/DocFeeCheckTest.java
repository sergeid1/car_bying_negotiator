package com.dealanalyzer.economics.fees;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.ai.ProhibitedClaims;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DocFeeCheckTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DocFeeCheck check = new DocFeeCheck(null, null, json);
  private final LocalDate today = LocalDate.of(2026, 10, 18);

  private ObjectNode terms(String label, long amount) {
    var terms = json.createObjectNode();
    terms.putArray("lineItems").addObject().put("originalLabel", label).put("category", "DEALER_FEE")
        .put("currency", "USD").put("amountMinor", amount).put("buyerVerified", true);
    return terms;
  }

  @Test void texasBoundaryIsAQuestionOnlyAboveTheSafeHarbor() {
    for (long amount : new long[] {22500, 22499, 0}) {
      var finding = check.evaluate("US-TX", terms("Doc fee", amount), today).getFirst();
      assertThat(finding.severity()).isEqualTo("INFORMATIONAL");
      assertThat(finding.buyerAction()).doesNotContain("OCCC", "filed");
    }
    var finding = check.evaluate("US-TX", terms("Doc fee", 22501), today).getFirst();
    assertThat(finding.reason()).contains("$225.01", "$225", "notification", "cost analysis", "2026-10-18");
    assertThat(finding.buyerAction()).contains("filed", "OCCC");
    assertThat(finding.uncertain()).isFalse();
    assertThat(finding.observedEvidence().at("/rule/effectiveFrom").asText()).isEqualTo("2024-07-11");
  }

  @Test void disclosureStatesExplainTheirRulesWithoutClaimingABreach() {
    var florida = check.evaluate("US-FL", terms("Dealer service fee", 99900), today).getFirst();
    assertThat(florida.severity()).isEqualTo("INFORMATIONAL");
    assertThat(florida.reason()).contains("no dollar cap", "advertised price", "costs and profit", "negotiable");
    var nc = check.evaluate("US-NC", terms("Administrative fee", 89900), today).getFirst();
    assertThat(nc.reason()).contains("no dollar cap", "posted", "separately identified", "negotiable");
    for (var finding : java.util.List.of(florida, nc)) {
      assertThat(ProhibitedClaims.contains(finding.reason() + finding.buyerAction())).isFalse();
      assertThat(finding.observedEvidence().at("/rule/reviewBy").asText()).isEqualTo("2027-01-18");
    }
  }

  @Test void recognitionIsExplicitAndKeepsEveryLineForTheCombinedFee() {
    var terms = terms("  DOCUMENT-PROCESSING-FEE ", 15000);
    ((com.fasterxml.jackson.databind.node.ArrayNode) terms.path("lineItems"))
        .add(terms("Doc fee", 15000).path("lineItems").get(0));
    var finding = check.evaluate("US-TX", terms, today).getFirst();
    assertThat(finding.observed().path("amountMinor").asLong()).isEqualTo(30000);
    assertThat(finding.observedEvidence().path("lines")).hasSize(2);
    assertThat(finding.reason()).contains("DOCUMENT-PROCESSING-FEE", "Doc fee", "$300.00");
    assertThat(check.evaluate("US-TX", terms("Office convenience package", 99900), today)).isEmpty();
    assertThat(check.evaluate("US-CA", terms("Doc fee", 99900), today)).isEmpty();
    assertThat(check.evaluate(null, terms("Doc fee", 99900), today)).isEmpty();
  }

  @Test void possibleAliasesAreReportedSeparatelyWithoutPushingTheClearFeeAboveTheSafeHarbor() {
    for (String label : java.util.List.of("Origination fee", "Procurement fee", "Dealer handling fee", "Clerical fee")) {
      var terms = terms("Doc fee", 19900);
      ((com.fasterxml.jackson.databind.node.ArrayNode) terms.path("lineItems"))
          .add(terms(label, 30000).path("lineItems").get(0));
      var finding = check.evaluate("US-TX", terms, today).getFirst();
      assertThat(finding.reason()).as(label).contains(label, "$199.00", "$300.00",
          "Combined total including possible aliases: $499.00", "may be a documentary fee under another name");
      assertThat(finding.observed().path("amountMinor").asLong()).isEqualTo(19900);
      assertThat(finding.observed().path("possibleDocFeeAmountMinor").asLong()).isEqualTo(30000);
      assertThat(finding.observed().path("combinedAmountMinor").asLong()).isEqualTo(49900);
      assertThat(finding.observedEvidence().at("/lines/1/recognitionTier").asText()).isEqualTo("POSSIBLE_DOC_FEE");
      assertThat(finding.buyerAction()).contains(label, "cover", "is it the documentary fee").doesNotContain("OCCC", "filed");
      assertThat(finding.severity()).isEqualTo("INFORMATIONAL");
      assertThat(ProhibitedClaims.contains(finding.reason() + finding.buyerAction())).isFalse();
    }
  }

  @Test void keywordVariantsAreRecognisedWithClearTermsTakingPrecedence() {
    for (String label : java.util.List.of("Doc. Fee", "DOCUMENTARY SVC FEE", "Admin-Fee", "Docs surcharge",
        "Document preparation", "Administration charge", "Processing surcharge", "Doc handling fee")) {
      var finding = check.evaluate("US-TX", terms(label, 30000), today).getFirst();
      assertThat(finding.observedEvidence().at("/lines/0/recognitionTier").asText()).as(label).isEqualTo("DOC_FEE");
      assertThat(finding.buyerAction()).contains("OCCC", "filed");
    }
    for (String label : java.util.List.of("Dealer Paperwork Charge", "Conveyance charge", "E-Filing charge",
        "Electronic Filing Service", "Dealer Svc Charge", "Service and handling charge")) {
      var finding = check.evaluate("US-TX", terms(label, 30000), today).getFirst();
      assertThat(finding.observedEvidence().at("/lines/0/recognitionTier").asText()).as(label).isEqualTo("POSSIBLE_DOC_FEE");
      assertThat(finding.reason()).contains(label, "Combined total including possible aliases: $300.00");
      assertThat(finding.buyerAction()).contains("cover", "documentary fee").doesNotContain("OCCC", "filed");
    }
    for (String label : java.util.List.of("window tint", "nitrogen tires", "GAP", "aftermarket protection"))
      assertThat(check.evaluate("US-TX", terms(label, 99900), today)).as(label).isEmpty();
  }

  @Test void clearFeesAboveTheSafeHarborStillAskAboutFilingAlongsidePossibleAliases() {
    var terms = terms("Documentation svc", 30000);
    ((com.fasterxml.jackson.databind.node.ArrayNode) terms.path("lineItems"))
        .add(terms("Origination charge", 10000).path("lineItems").get(0));
    var finding = check.evaluate("US-TX", terms, today).getFirst();
    assertThat(finding.reason()).contains("Identified documentary fee total: $300.00",
        "Combined total including possible aliases: $400.00");
    assertThat(finding.buyerAction()).contains("OCCC", "Origination charge", "cover");
  }

  @Test void staleRulesAndUnusableAmountsCannotSupportAComparison() {
    assertThat(check.evaluate("US-TX", terms("Doc fee", 50000), LocalDate.of(2024, 7, 10))).isEmpty();
    assertThat(check.evaluate("US-TX", terms("Doc fee", 50000), LocalDate.of(2027, 1, 19))).isEmpty();
    var terms = terms("Doc fee", 50000);
    ((ObjectNode) terms.path("lineItems").get(0)).putNull("amountMinor");
    var unreadable = check.evaluate("US-TX", terms, today).getFirst();
    assertThat(unreadable.observedEvidence().path("lines")).hasSize(1);
    assertThat(unreadable.observed().path("amountMinor").isNull()).isTrue();
    assertThat(unreadable.reason()).contains("unavailable for a USD comparison");
    assertThat(unreadable.buyerAction()).doesNotContain("filed");
    terms = terms("Doc fee", 50000);
    ((ObjectNode) terms.path("lineItems").get(0)).put("buyerVerified", false);
    assertThat(check.evaluate("US-TX", terms, today).getFirst().uncertain()).isTrue();
  }
}
