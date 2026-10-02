package com.dealanalyzer.purchase;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OfferCatalogTest {
  private final ObjectMapper json = new ObjectMapper();
  private ObjectNode config() throws Exception {
    try (var resource = getClass().getResourceAsStream("/purchase/offer.v1.json")) {
      return (ObjectNode) json.readTree(resource);
    }
  }
  @Test void shippedOfferIsFreeAndEveryNonOneTimeKindFailsClosed() throws Exception {
    var config = config();
    assertThat(OfferCatalog.validate(json, config, "DEVELOPMENT_NO_CHARGE")).isNull();
    for (String kind : List.of("SUBSCRIPTION", "RECURRING", "SUCCESS_FEE", "UNKNOWN")) {
      ((ObjectNode) config.path("offer")).put("kind", kind);
      assertThatThrownBy(() -> OfferCatalog.validate(json, config, "DEVELOPMENT_NO_CHARGE"))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ONE_TIME_PER_CASE");
    }
  }
  @Test void everyApprovalFieldIsRequiredAndPriceComesFromApproval() throws Exception {
    var config = config();
    config.putObject("approval").put("experimentKey", "offer-price.v1").put("amountMinor", 1700)
        .put("currency", "USD").put("approver", "operator").put("approvedAt", "2026-09-01T00:00:00Z")
        .put("rationale", "An experiment value, not copied from a competitor's advertised price.")
        .put("notCopiedFromCompetitorAdvertisedPrice", true);
    assertThat(OfferCatalog.validate(json, config, "DEVELOPMENT_NO_CHARGE").at("/price/amountMinor").asInt()).isEqualTo(1700);
    for (String field : List.of("experimentKey", "amountMinor", "currency", "approver", "approvedAt", "rationale", "notCopiedFromCompetitorAdvertisedPrice")) {
      var incomplete = config.deepCopy(); ((ObjectNode) incomplete.get("approval")).remove(field);
      assertThat(OfferCatalog.validate(json, incomplete, "DEVELOPMENT_NO_CHARGE")).as(field).isNull();
    }
    var missingStatement = config.deepCopy();
    ((ObjectNode) missingStatement.get("approval")).put("rationale", "A price experiment.");
    assertThat(OfferCatalog.validate(json, missingStatement, "DEVELOPMENT_NO_CHARGE")).isNull();
    ((ObjectNode) config.get("approval")).put("approvedAt", "not-a-time");
    assertThat(OfferCatalog.validate(json, config, "DEVELOPMENT_NO_CHARGE")).isNull();
  }
  @Test void noChargeAdapterCannotServeAnyProductionProfileEvenWithNoOffer() {
    for (String profile : List.of("production", "prod", "pilot", "PROD")) {
      var environment = new MockEnvironment(); environment.setActiveProfiles(profile);
      assertThatThrownBy(() -> new CheckoutProductionGuard(environment, new DevelopmentNoChargeCheckout()).afterSingletonsInstantiated())
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("DevelopmentNoChargeCheckout");
    }
    assertThatCode(() -> new CheckoutProductionGuard(new MockEnvironment(), new DevelopmentNoChargeCheckout()).afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }
}
