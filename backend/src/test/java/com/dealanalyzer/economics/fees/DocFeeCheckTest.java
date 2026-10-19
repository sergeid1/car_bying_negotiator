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
    assertThat(check.evaluate("US-TX", terms("Doc fee", 22500), today)).isEmpty();
    assertThat(check.evaluate("US-TX", terms("Doc fee", 22499), today)).isEmpty();
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
    var terms = terms("  CLERICAL-FEE ", 15000);
    ((com.fasterxml.jackson.databind.node.ArrayNode) terms.path("lineItems"))
        .add(terms("Doc fee", 15000).path("lineItems").get(0));
    var finding = check.evaluate("US-TX", terms, today).getFirst();
    assertThat(finding.observed().path("amountMinor").asLong()).isEqualTo(30000);
    assertThat(finding.observedEvidence().path("lines")).hasSize(2);
    assertThat(finding.reason()).contains("CLERICAL-FEE", "Doc fee", "$300.00");
    assertThat(check.evaluate("US-TX", terms("Office convenience package", 99900), today)).isEmpty();
    assertThat(check.evaluate("US-CA", terms("Doc fee", 99900), today)).isEmpty();
    assertThat(check.evaluate(null, terms("Doc fee", 99900), today)).isEmpty();
  }

  @Test void staleRulesAndUnusableAmountsCannotSupportAComparison() {
    assertThat(check.evaluate("US-TX", terms("Doc fee", 50000), LocalDate.of(2024, 7, 10))).isEmpty();
    assertThat(check.evaluate("US-TX", terms("Doc fee", 50000), LocalDate.of(2027, 1, 19))).isEmpty();
    var terms = terms("Doc fee", 50000);
    ((ObjectNode) terms.path("lineItems").get(0)).putNull("amountMinor");
    assertThat(check.evaluate("US-TX", terms, today)).isEmpty();
    terms = terms("Doc fee", 50000);
    ((ObjectNode) terms.path("lineItems").get(0)).put("buyerVerified", false);
    assertThat(check.evaluate("US-TX", terms, today).getFirst().uncertain()).isTrue();
  }
}
