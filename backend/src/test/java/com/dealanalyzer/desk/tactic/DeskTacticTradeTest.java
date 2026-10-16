package com.dealanalyzer.desk.tactic;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.trade.desk.TradeDeskCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeskTacticTradeTest {
  private final ObjectMapper json = new ObjectMapper();
  private final DeskTacticPolicy policy = new DeskTacticPolicy();
  private JsonNode fixtures() throws Exception { return json.readTree(getClass().getResourceAsStream("/fixtures/desk/trade-tactics.json")); }
  private JsonNode terms(JsonNode fixture) {
    var terms = json.createObjectNode(); var candidates = terms.putArray("candidates");
    fixture.path("fields").fields().forEachRemaining(field -> candidates.addObject().put("field", field.getKey()).put("value", field.getValue().asText())
        .put("buyerVerified", true).set("evidence", json.createObjectNode().put("pageNumber", 2).put("x", .1).put("y", .2).put("width", .3).put("height", .1)));
    return terms;
  }
  private List<DeskTacticPolicy.Finding> findings(JsonNode fixture) throws Exception {
    return policy.tradeFindings(new DeskTacticPolicy.Input(terms(fixture), null),
        json.treeToValue(fixture.path("trade"), TradeDeskCheck.Result.class), fixture.path("trade"));
  }

  @Test void syntheticTradeResultsAreNamedWithoutRecalculatingTheirDollarEffects() throws Exception {
    var fixtures = fixtures();
    assertThat(fixtures.path("label").asText()).contains("Synthetic");
    for (var fixture : fixtures.path("worksheets")) {
      var findings = findings(fixture);
      if (!fixture.has("code")) { assertThat(findings).isEmpty(); continue; }
      assertThat(findings).singleElement().satisfies(f -> {
        assertThat(f.code()).isEqualTo(fixture.path("code").asText());
        assertThat(f.tradeFindingCode()).isEqualTo(fixture.path("tradeCode").asText());
        assertThat(f.totalEffectMinor()).isEqualTo(fixture.path("totalMinor").asLong());
        assertThat(f.monthlyEffectMinor()).isEqualTo(fixture.has("monthlyMinor") ? fixture.path("monthlyMinor").asLong() : null);
        assertThat(f.policyVersion()).isEqualTo("desk-tactics.v3");
        assertThat(f.questionReview()).isEqualTo("PENDING_OPERATOR_REVIEW");
        assertThat(f.evidenceRegions().getFirst().region().path("pageNumber").asInt()).isEqualTo(2);
        assertThat(f.evidenceRegions().getLast().region()).isEqualTo(fixture.path("trade"));
      });
    }
    assertThat(terms(fixtures.at("/worksheets/0")).findValuesAsText("field")).doesNotContain("NEGATIVE_EQUITY");
  }

  @Test void missingLoanCostRetainsKnownPrincipalAndExplainsUnavailablePaymentAndInterest() throws Exception {
    var fixture = (ObjectNode) fixtures().at("/worksheets/0").deepCopy();
    ((ObjectNode) fixture.path("trade")).set("negativeEquityCost", json.createObjectNode().put("status", "UNAVAILABLE").put("reason", "Loan term is missing."));
    assertThat(findings(fixture)).singleElement().satisfies(f -> {
      assertThat(f.monthlyEffectMinor()).isNull();
      assertThat(f.totalEffectMinor()).isEqualTo(300000);
      assertThat(f.noDollarEffectReason()).isEqualTo("Loan term is missing.");
      assertThat(f.effectBasis()).contains("principal amount only", "PAPERWORK_PAYOFF");
    });
  }

  @Test void freshCasesAndUnchangedPriceDoNotNameAllowanceTacticEvenWithAnAboveValueAllowance() throws Exception {
    for (Long increase : new Long[] {null, 0L, -10000L}) {
      var fixture = (ObjectNode) fixtures().at("/worksheets/1").deepCopy();
      ((ObjectNode) fixture.at("/trade/priceTogether")).put("priceIncreaseMinor", increase);
      assertThat(findings(fixture)).isEmpty();
    }
    var valuationFixture = (ObjectNode) fixtures().at("/worksheets/1").deepCopy();
    ((ObjectNode) valuationFixture.at("/trade/priceTogether")).put("reference", "VALUATION_HIGH");
    ((ObjectNode) valuationFixture.path("trade")).putNull("bestOffer").set("valuation", json.createObjectNode().put("source", "SYNTHETIC_TRADE_VALUATION"));
    assertThat(findings(valuationFixture)).singleElement().satisfies(f -> assertThat(f.effectBasis()).contains("VALUATION_HIGH"));
    for (long net : new long[] {0, 50000}) {
      var fixture = (ObjectNode) fixtures().at("/worksheets/1").deepCopy();
      ((ObjectNode) fixture.at("/trade/priceTogether")).put("priceIncreaseMinor", net == 0 ? 100000 : 50000).put("netEffectMinor", net);
      assertThat(findings(fixture)).singleElement().satisfies(f -> assertThat(f.totalEffectMinor()).isEqualTo(net));
    }
  }

  @Test void noTradeOrDifferentTradeVinProducesNoTradeTacticsAndCopyStaysNeutral() throws Exception {
    var fixture = (ObjectNode) fixtures().at("/worksheets/0").deepCopy();
    ((ObjectNode) fixture.path("trade")).set("findings", json.createArrayNode().add(json.createObjectNode().put("code", "TRADE_VIN_DIFFERENT").put("severity", "BLOCKING")));
    assertThat(findings(fixture)).isEmpty();
    assertThat(policy.tradeFindings(new DeskTacticPolicy.Input(terms(fixture), null), null, null)).isEmpty();
    var vocabulary = Class.forName("com.dealanalyzer.explanation.ClaimVocabulary");
    var scan = vocabulary.getDeclaredMethod("terms", String.class); scan.setAccessible(true);
    for (var worksheet : fixtures().path("worksheets")) for (var finding : findings(worksheet)) {
      var claims = (List<?>) scan.invoke(null, finding.summary() + " " + finding.deskQuestion());
      assertThat(claims.stream().map(Object::toString).toList()).doesNotContain("STRONG", "FAIR", "POOR", "EXACT");
    }
  }
}
