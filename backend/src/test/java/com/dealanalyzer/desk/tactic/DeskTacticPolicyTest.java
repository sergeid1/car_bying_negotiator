package com.dealanalyzer.desk.tactic;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.finance.FinancingCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskTacticPolicyTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DeskTacticPolicy policy = new DeskTacticPolicy();
  private ObjectNode terms(String... values) {
    var terms = json.createObjectNode(); var candidates = terms.putArray("candidates");
    for (int i = 0; i < values.length; i += 2) candidates.addObject().put("field", values[i]).put("value", values[i + 1])
        .put("unit", values[i].equals("APR") ? "PERCENT" : values[i].equals("TERM_MONTHS") ? "MONTH" : "USD_MINOR")
        .put("buyerVerified", false).set("evidence", json.createObjectNode().put("pageNumber", 1).put("x", .1).put("y", .2).put("width", .3).put("height", .1));
    return terms;
  }
  private DeskTacticPolicy.Input input(JsonNode terms, String ownApr, Integer ownTerm) {
    return new DeskTacticPolicy.Input(terms, new DeskTacticPolicy.BuyerOffer(ownApr == null ? null : new BigDecimal(ownApr), ownTerm,
        json.createObjectNode().put("source", "synthetic_buyer_offer")));
  }

  @Test void labelledSyntheticWorksheetsCoverEachRuleAndCleanWorksheet() throws Exception {
    var fixtures = json.readTree(getClass().getResourceAsStream("/fixtures/desk/tactics.json"));
    assertThat(fixtures.path("label").asText()).contains("Synthetic");
    for (var fixture : fixtures.path("worksheets")) {
      var values = new java.util.ArrayList<String>();
      fixture.path("fields").fields().forEachRemaining(field -> { values.add(field.getKey()); values.add(field.getValue().asText()); });
      var input = input(terms(values.toArray(String[]::new)), fixture.path("ownApr").asText(null), fixture.has("ownTerm") ? fixture.path("ownTerm").asInt() : null);
      var findings = policy.evaluate(input);
      assertThat(json.valueToTree(findings).findValuesAsText("code")).as(fixture.path("name").asText())
          .containsExactlyElementsOf(json.convertValue(fixture.path("codes"), List.class));
      assertThat(policy.evaluate(input)).isEqualTo(findings);
      for (var finding : findings) {
        assertThat(finding.questionReview()).isEqualTo("PENDING_OPERATOR_REVIEW");
        assertThat(finding.policyVersion()).isEqualTo(DeskTacticPolicy.VERSION);
        assertThat(finding.evidenceRegions()).isNotEmpty();
        assertThat(finding.verified()).isFalse();
        assertThat(finding.totalEffectMinor()).isNotNull();
        assertThat(finding.noDollarEffectReason()).isNull();
        assertThat(finding.deskQuestion()).isNotBlank();
      }
      if (fixture.has("totalMinor")) {
        assertThat(findings.getFirst().monthlyEffectMinor()).isEqualTo(fixture.path("monthlyMinor").asLong());
        assertThat(findings.getFirst().totalEffectMinor()).isEqualTo(fixture.path("totalMinor").asLong());
      }
    }
  }

  @Test void toleranceIsStrictlyAboveOneDollarAndDownIsNeverAssumedZero() {
    for (String payment : List.of("49900", "50000", "50100"))
      assertThat(policy.evaluate(input(terms("AMOUNT_FINANCED", "3000000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", payment), null, null))).isEmpty();
    assertThat(policy.evaluate(input(terms("AMOUNT_FINANCED", "3000000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", "50101"), null, null)))
        .singleElement().satisfies(f -> assertThat(f.monthlyEffectMinor()).isEqualTo(101));
    assertThat(policy.evaluate(input(terms("SELLING_PRICE", "3000000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", "54000"), null, null))).isEmpty();
    assertThat(policy.evaluate(input(terms("SELLING_PRICE", "3000000", "CASH_DOWN", "0", "TRADE_ALLOWANCE", "800000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", "54000"), null, null))).isEmpty();
  }

  @Test void interestCounterfactualsIncludeActualFinalPaymentRounding() {
    var calculator = new FinancingCalculator();
    var dealer = calculator.partialSchedule(3000000L, new BigDecimal("8"), 72);
    var shorter = calculator.partialSchedule(3000000L, new BigDecimal("8"), 60);
    var lower = calculator.partialSchedule(3000000L, new BigDecimal("6"), 72);
    var findings = policy.evaluate(input(terms("AMOUNT_FINANCED", "3000000", "APR", "8", "TERM_MONTHS", "72"), "6", 60));
    assertThat(findings).hasSize(2);
    assertThat(findings.get(0).totalEffectMinor()).isEqualTo(dealer.interestMinor() - shorter.interestMinor());
    assertThat(findings.get(0).monthlyEffectMinor()).isEqualTo(dealer.paymentMinor() - shorter.paymentMinor()).isNegative();
    assertThat(findings.get(1).totalEffectMinor()).isEqualTo(dealer.interestMinor() - lower.interestMinor());
    assertThat(findings.get(1).monthlyEffectMinor()).isEqualTo(dealer.paymentMinor() - lower.paymentMinor());
    assertThat(findings.get(1).effectBasis()).contains("excluding differences in lender fees");
  }

  @Test void missingOrUnsupportedInputsKeepObservedTacticButExplainAbsentDollarEffect() {
    for (var terms : List.of(terms("APR", "8", "TERM_MONTHS", "72"), terms("AMOUNT_FINANCED", "3000000", "APR", "8", "TERM_MONTHS", "1201"))) {
      var findings = policy.evaluate(input(terms, "6", 60));
      assertThat(findings).isNotEmpty().allSatisfy(f -> {
        assertThat(f.totalEffectMinor()).isNull();
        assertThat(f.monthlyEffectMinor()).isNull();
        assertThat(f.noDollarEffectReason()).isNotBlank();
      });
    }
    assertThat(policy.evaluate(input(terms("APR", "bad", "TERM_MONTHS", "72"), "6", null))).isEmpty();
    assertThat(policy.evaluate(input(terms("APR", "8", "TERM_MONTHS", "72"), "-1", null))).isEmpty();
  }

  @Test void anyHigherSupportedAprIsComparedAndZeroAprLongerTermHasZeroExtraInterest() {
    assertThat(policy.evaluate(input(terms("AMOUNT_FINANCED", "3000000", "APR", "6.01", "TERM_MONTHS", "60"), "6", 60)))
        .singleElement().satisfies(f -> assertThat(f.code()).isEqualTo("APR_ABOVE_OWN_OFFER"));
    assertThat(policy.evaluate(input(terms("AMOUNT_FINANCED", "3000000", "APR", "0", "TERM_MONTHS", "72"), null, 60)))
        .singleElement().satisfies(f -> assertThat(f.totalEffectMinor()).isZero());
  }

  @Test void ambiguousPrincipalAndIncorrectUnitsAreNotChosenOrReplacedWithPriceMinusDown() {
    var terms = terms("SELLING_PRICE", "3000000", "CASH_DOWN", "0", "AMOUNT_FINANCED", "3000000",
        "AMOUNT_FINANCED", "2900000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", "54000");
    assertThat(policy.evaluate(input(terms, null, null))).isEmpty();
    var wrongUnit = terms("AMOUNT_FINANCED", "3000000", "APR", "0", "TERM_MONTHS", "60", "MONTHLY_PAYMENT", "54000");
    ((ObjectNode) wrongUnit.at("/candidates/0")).put("unit", "EUR_MINOR");
    assertThat(policy.evaluate(input(wrongUnit, null, null))).isEmpty();
  }

  @Test void verifiedTermsRetainEvidenceAndLaterRulesCanConsumeAnExternalTradeResult() {
    var terms = terms("AMOUNT_FINANCED", "3000000", "APR", "8", "TERM_MONTHS", "60");
    terms.path("candidates").forEach(term -> ((ObjectNode) term).put("buyerVerified", true));
    var input = input(terms, "6", 60);
    assertThat(policy.evaluate(input)).singleElement().satisfies(f -> {
      assertThat(f.verified()).isTrue();
      assertThat(f.evidenceRegions().getFirst().region().path("width").asDouble()).isEqualTo(.3);
    });
    // Future adapter closes over the actual 103 record; no speculative trade DTO enters this policy.
    assertThat(policy.evaluate(input, List.of(ignored -> java.util.Optional.empty()))).isEqualTo(policy.evaluate(input));
  }
}
