package com.dealanalyzer.report;

import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.plan.PriceBoundaryPolicy.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreReviewPolicyTest {
  @Test
  void releaseCorpusHasNoFalseMatches() throws Exception {
    var json = new ObjectMapper();
    Currency usd = Currency.getInstance("USD");
    var policy = new Policy("tax.v1", "fixture", usd, new BigDecimal("0.10"), Set.of(LineType.SELLING_PRICE),
        false, 2, RoundingMode.HALF_UP, BigDecimal.ZERO);
    var offer = new Offer("offer", 1, usd, List.of(new LineItem("price", "Selling price",
        new Money(new BigDecimal("10000"), usd), LineType.SELLING_PRICE, TaxTreatment.TAXABLE, Eligibility.NOT_APPLICABLE)),
        new Money(new BigDecimal("11000"), usd), "quote");
    var cash = new CashDealEconomicsCalculator().calculate(offer, policy);
    var storedCash = json.readValue(json.writeValueAsString(cash), Result.class);
    var terms = json.readTree("{\"sellingPrice\":1000000}");
    try (var stream = getClass().getResourceAsStream("/fixtures/review/core-report-safety-corpus.json")) {
      for (var fixture : json.readTree(stream)) {
        long cap = fixture.get("overLimit").asBoolean() ? 900_000 : 1_500_000;
        var plan = new Plan("v1", "USD", new Boundary(900_000, 1_000_000, 1_500_000, cap),
            new Boundary(990_000, 1_100_000, 1_650_000, 1_650_000), cap, 1_650_000L, null, List.of(), "ESTIMATED", "fixture");
        String status = new CoreReviewPolicy().review(fixture.get("current").asBoolean(), cash,
            fixture.get("changed").asBoolean() ? json.readTree("{\"sellingPrice\":1000000,\"addOn\":39900}") : terms,
            fixture.get("baseline").asBoolean() ? terms : null, storedCash, plan, fixture.get("market").asBoolean());
        assertThat(status).as(fixture.get("name").asText()).isEqualTo(fixture.get("expected").asText());
      }
    }
  }
}
