package com.dealanalyzer.review;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RevisedOfferPolicyTest {
  private final ObjectMapper json = new ObjectMapper();




  @Test
  void retainsUnchangedUncertainTaxClaimsAndDistinctDuplicateCharges() throws Exception {
    var terms = json.readTree("""
        {"lineItems":[
          {"originalLabel":"Protection","amountMinor":50000,"currency":"USD","category":"ADD_ON","taxClaim":"UNKNOWN"},
          {"originalLabel":"Protection","amountMinor":10000,"currency":"USD","category":"ADD_ON","taxClaim":"UNKNOWN"}]}
        """);
    assertThat(new RevisedOfferPolicy().compare("BASELINE", terms, terms)).hasSize(2)
        .allSatisfy(d -> {
          assertThat(d.kind()).isEqualTo("UNCERTAIN");
          assertThat(d.uncertain()).isTrue();
        });
  }
  @Test
  void hiddenAddOnFixturesShowCompletePreTaxDifferencesAndSeparatelyCalculatedTaxEffects() throws Exception {
    var fixtures = json.readTree(getClass().getResourceAsStream("/fixtures/review/hidden-add-ons.json"));
    for (var fixture : fixtures) {
      var currency = java.util.Currency.getInstance("USD");
      var policy = new com.dealanalyzer.economics.CashDealEconomics.Policy("fixture.v1", "NC", currency,
          new java.math.BigDecimal(fixture.path("taxRate").asText()),
          java.util.Set.of(com.dealanalyzer.economics.CashDealEconomics.LineType.SELLING_PRICE,
              com.dealanalyzer.economics.CashDealEconomics.LineType.DEALER_DISCOUNT,
              com.dealanalyzer.economics.CashDealEconomics.LineType.ADD_ON),
          false, 2, java.math.RoundingMode.HALF_UP, java.math.BigDecimal.ONE);
      var before = new com.dealanalyzer.economics.CashDealEconomics.Offer("before", 1, currency,
          java.util.List.of(line(fixture, "sellingPrice", "SELLING_PRICE", "TAXABLE"),
              line(fixture, "initialDiscount", "DEALER_DISCOUNT", "TAXABLE")), null, null);
      var after = new com.dealanalyzer.economics.CashDealEconomics.Offer("after", 2, currency,
          java.util.List.of(line(fixture, "sellingPrice", "SELLING_PRICE", "TAXABLE"),
              line(fixture, "revisedDiscount", "DEALER_DISCOUNT", "TAXABLE"),
              line(fixture, "protection", "ADD_ON", "TAXABLE"),
              line(fixture, "governmentFee", "GOVERNMENT_CHARGE", "NOT_TAXABLE")), null, null);
      var calculator = new com.dealanalyzer.economics.CashDealEconomicsCalculator();
      var delta = new RevisedOfferPolicy().compareCash(
          json.valueToTree(calculator.calculate(before, policy)), json.valueToTree(calculator.calculate(after, policy)));
      assertThat(delta.path("preTaxDeltaMinor").asLong()).as(fixture.path("name").asText())
          .isEqualTo(fixture.path("expectedPreTaxDeltaMinor").asLong());
      assertThat(delta.path("taxEffectMinor").asLong()).isEqualTo(fixture.path("expectedTaxEffectMinor").asLong());
      assertThat(delta.path("outTheDoorDeltaMinor").asLong()).isEqualTo(fixture.path("expectedOutTheDoorDeltaMinor").asLong());
    }
  }

  private com.dealanalyzer.economics.CashDealEconomics.LineItem line(
      com.fasterxml.jackson.databind.JsonNode fixture, String name, String type, String tax) {
    return new com.dealanalyzer.economics.CashDealEconomics.LineItem(name, name,
        new com.dealanalyzer.economics.CashDealEconomics.Money(
            new java.math.BigDecimal(fixture.path(name).asText()), java.util.Currency.getInstance("USD")),
        com.dealanalyzer.economics.CashDealEconomics.LineType.valueOf(type),
        com.dealanalyzer.economics.CashDealEconomics.TaxTreatment.valueOf(tax),
        com.dealanalyzer.economics.CashDealEconomics.Eligibility.VERIFIED);
  }
  @Test
  void changingChargeClassificationIsMaterialEvenWhenTheAmountDoesNotChange() throws Exception {
    var before = json.readTree("""
        {"lineItems":[{"originalLabel":"Protection","amountMinor":100,"currency":"USD","category":"ADD_ON"}]}
        """);
    var after = json.readTree("""
        {"lineItems":[{"originalLabel":"Protection","amountMinor":100,"currency":"USD","category":"DEALER_FEE"}]}
        """);
    assertThat(new RevisedOfferPolicy().compare("PREVIOUS", before, after)).singleElement()
        .satisfies(d -> assertThat(d.severity()).isEqualTo("MATERIAL"));
  }
  @Test
  void showsSmallChangesMissingTermsAndHiddenAddOnsWithoutTreatingNewEvidenceIdsAsChanges() throws Exception {
    var expected = json.readTree("""
        {"candidates":[{"field":"APR","value":"5","unit":"PERCENT"},{"field":"TERM_MONTHS","value":"60","unit":"MONTH"}],
         "lineItems":[{"id":"old","originalLabel":"Selling price","amountMinor":2500000,"currency":"USD","category":"SELLING_PRICE"}]}
        """);
    var observed = json.readTree("""
        {"candidates":[{"field":"APR","value":"5.01","unit":"PERCENT"}],
         "lineItems":[{"id":"new","originalLabel":"Selling price","amountMinor":2500000,"currency":"USD","category":"SELLING_PRICE"},
         {"id":"addon","originalLabel":"Protection","amountMinor":89900,"currency":"USD","category":"UNKNOWN","confidence":0.6}]}
        """);
    var differences = new RevisedOfferPolicy().compare("BASELINE", expected, observed);
    assertThat(differences).hasSize(3);
    assertThat(differences).anySatisfy(d -> {
      assertThat(d.term()).contains("APR");
      assertThat(d.delta()).isEqualByComparingTo("0.01");
      assertThat(d.severity()).isEqualTo("INFORMATIONAL");
    });
    assertThat(differences).anySatisfy(d -> {
      assertThat(d.term()).contains("TERM_MONTHS");
      assertThat(d.kind()).isEqualTo("MISSING");
      assertThat(d.delta()).isNull();
    });
    assertThat(differences).anySatisfy(d -> {
      assertThat(d.term()).contains("protection");
      assertThat(d.kind()).isEqualTo("ADDED");
      assertThat(d.uncertain()).isTrue();
      assertThat(d.observedEvidence().path("id").asText()).isEqualTo("addon");
      assertThat(d.confidence()).isEqualTo(0.6);
    });
  }
}
