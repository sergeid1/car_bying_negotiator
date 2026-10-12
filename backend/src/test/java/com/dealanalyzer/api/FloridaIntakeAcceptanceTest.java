package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.economics.SupportedJurisdictions;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic Buyer cases with real Census county intersections and sourced 2026 packs. */
class FloridaIntakeAcceptanceTest {
  @TempDir Path directory;

  @Test void floridaCasesRecordTheCountyAndACrossCountyZipRequiresTheBuyersChoice() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("synthetic-florida-intake")) {
      var client = app.client();
      var supported = app.context().getBean(SupportedJurisdictions.class);
      assertThat(supported.all()).containsExactly("US-FL", "US-NC");
      assertThat(supported.supports("US-FL")).isTrue();
      assertThat(supported.supports("US-TX")).isFalse();
      var created = post(client, "/api/v1/cases", intake("32003", null), "single");
      assertThat(created.at("/case/buyerJurisdiction").asText()).isEqualTo("US-FL:Clay");
      String id = created.at("/case/id").asText(), token = created.path("accessToken").asText();
      assertThat(get(client, "/api/v1/cases/" + id, token).path("buyerJurisdiction").asText()).isEqualTo("US-FL:Clay");
      var refusal = client.post().uri("/api/v1/cases").contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "crossing").body(intake("32024", null))
          .exchange((sent, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
          });
      assertThat(refusal.path("reason").asText()).isEqualTo("POSTAL_CODE_CROSSES_COUNTY_LINE");
      assertThat(refusal.path("field").asText()).isEqualTo("buyerCounty");
      assertThat(refusal.path("candidateJurisdictions").toString()).isEqualTo("[\"Columbia\",\"Suwannee\"]");
      var chosen = post(client, "/api/v1/cases", intake("32024", "Suwannee"), "crossing");
      assertThat(chosen.at("/case/buyerJurisdiction").asText()).isEqualTo("US-FL:Suwannee");
      var wrong = client.post().uri("/api/v1/cases").contentType(MediaType.APPLICATION_JSON)
          .header("Idempotency-Key", "wrong-county").body(intake("32024", "Clay"))
          .exchange((sent, response) -> response.getStatusCode().value());
      assertThat(wrong).isEqualTo(400);

      var database = app.context().getBean(JdbcTemplate.class);
      assertThat(database.queryForObject("select count(*) from florida_county_surtaxes", Integer.class)).isEqualTo(67);
      assertThat(database.queryForObject("select count(distinct county_name) from postal_counties", Integer.class)).isEqualTo(67);
      var policies = app.context().getBean(CashEconomicsPersistence.class);
      assertThat(policies.requirePolicy("US-FL:Suwannee").taxComponents().get(1).rate()).isEqualByComparingTo(".01");
      assertThat(policies.requirePolicy("US-FL:Collier").taxComponents().get(1).rate()).isZero();
      assertThat(policies.requirePolicy("US-FL:Palm Beach").taxComponents().get(1).rate()).isEqualByComparingTo(".005");
      assertThat(policies.requirePolicy("US-FL:Martin").taxComponents().get(1).rate()).isEqualByComparingTo(".005");
      for (String county : database.queryForList("select county_name from florida_county_surtaxes", String.class)) {
        var policy = policies.requirePolicy("US-FL:" + county);
        assertThat(policy.version()).startsWith("US-FL.cash.2026.v1:");
        assertThat(policy.taxComponents()).allSatisfy(component -> {
          assertThat(component.citation()).contains("https://");
          assertThat(component.checkedOn()).isEqualTo("2026-10-12");
        });
      }
      // Merely adding a future pack does not enable a new state today.
      database.update("insert into jurisdiction_policies select 'future-test', 'US-TX', currency, tax_rate, taxable_line_types, trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance, policy_json, timestamp with time zone '2099-01-01 00:00:00+00' from jurisdiction_policies where policy_version = 'US-NC.cash.v1'");
      assertThat(supported.supports("US-TX")).isFalse();
    }
  }

  private static Map<String, Object> intake(String zip, String county) {
    var request = new HashMap<String, Object>();
    request.put("buyerPostalCode", zip); request.put("dealerPostalCode", "32003");
    request.put("purchaseMethod", "CASH");
    if (county != null) request.put("buyerCounty", county);
    request.put("consents", Map.of(
        "serviceProcessing", Map.of("granted", true, "policyVersion", "service-processing.v1"),
        "humanReview", Map.of("granted", false, "policyVersion", "human-review.v1"),
        "anonymizedResearch", Map.of("granted", false, "policyVersion", "anonymized-research.v1")));
    return request;
  }
}
