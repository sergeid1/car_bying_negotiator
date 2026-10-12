package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.pilot.PilotEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;

class EvidenceMeasurementAcceptanceTest {
  private static final String PATH = "/api/v1/operator/evidence/metrics?from=2020-01-01T00:00:00Z&to=2100-01-01T00:00:00Z";
  @TempDir Path directory;

  @Test void developmentAdaptersMeasureCallsCoverageAndPrivacyWithExplicitDenominators() throws Exception {
    var settings = new ArrayList<>(List.of(ServiceOperationsAcceptanceTest.SETTINGS));
    settings.add("--evidence.budget.per-case-cost-usd=0.25");
    settings.add("--evidence.budget.p95-latency-millis=500");
    try (var app = new HttpAcceptanceHarness(directory).start("evidence", false, settings.toArray(String[]::new))) {
      var first = create(app, "Accord", 2021);
      var unavailable = create(app, "Civic", 2021);
      var thin = create(app, "Accord", 2010);
      String id = first.at("/case/id").asText(), token = first.path("accessToken").asText();
      post(app.client(), "/api/v1/cases/" + id + "/market-assessments", Map.of(), "repeat", token);
      // Real development listing adapter, in addition to the market adapters exercised by create().
      post(app.client(), "/api/v1/cases/" + id + "/vehicle-evidence",
          Map.of("vin", "1HGCM82633A004352", "listingUrl", "https://licensed.example/vehicles/1HGCM82633A004352",
              "confirmed", true), "listing", token);
      var events = app.context().getBean(PilotEvents.class);
      events.record(id, PilotEvents.Type.MARKET_COVERAGE, PilotEvents.Category.UNAVAILABLE, null);
      events.record(id, PilotEvents.Type.MARKET_COVERAGE, PilotEvents.Category.AVAILABLE, null);
      events.record(unavailable.at("/case/id").asText(), PilotEvents.Type.MARKET_COVERAGE, PilotEvents.Category.UNAVAILABLE, null);
      assertThatThrownBy(() -> get(app.client(), PATH)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> operator(app, PATH, token)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
      assertThatThrownBy(() -> operator(app, PATH, ServiceOperationsAcceptanceTest.REVIEWER)).isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThatThrownBy(() -> operator(app, PATH.replace("2100", "2019"), ServiceOperationsAcceptanceTest.SUPPORT))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);
      ServiceOperationsAcceptanceTest.SqlCapture.statements.clear();
      var report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/window/fromInclusive").asText()).isEqualTo("2020-01-01T00:00:00Z");
      assertThat(report.at("/observed/calls").asInt()).isEqualTo(5);
      assertThat(report.at("/observed/casesWithCalls").asInt()).isEqualTo(3);
      assertThat(report.at("/observed/callsWithKnownCost").asInt()).isEqualTo(5);
      assertThat(report.at("/observed/observedCostPerCaseUsd").decimalValue()).isZero();
      assertThat(report.at("/observed/costComparison").asText()).isEqualTo("WITHIN");
      assertThat(report.path("providers").findValuesAsText("operation")).contains("MARKET_RETRIEVAL", "LISTING_RETRIEVAL");
      assertThat(report.path("providers").findValuesAsText("status")).contains("SUCCESS", "PROVIDER_UNAVAILABLE");
      var jurisdiction = jurisdiction(report, "US-NC");
      assertThat(jurisdiction.path("jurisdiction").asText()).isEqualTo("US-NC");
      assertThat(jurisdiction.path("casesAttempted").asInt()).isEqualTo(3);
      assertThat(jurisdiction.path("casesWithAssessment").asInt()).isEqualTo(3);
      assertThat(jurisdiction.path("assessmentsAvailable").asInt()).isEqualTo(1);
      assertThat(jurisdiction.path("assessmentsUnavailable").asInt()).isEqualTo(1);
      assertThat(jurisdiction.path("assessmentsTooThin").asInt()).isEqualTo(1);
      assertThat(jurisdiction.path("casesWithCoverageEvent").asInt()).isEqualTo(2);
      assertThat(jurisdiction.path("reportEvidenceAvailable").asInt()).isEqualTo(1);
      assertThat(jurisdiction.path("reportEvidenceUnavailable").asInt()).isEqualTo(1);
      assertThat(jurisdiction.path("comparablesEvaluated").asInt()).isEqualTo(16);
      assertThat(jurisdiction.path("comparablesIncluded").asInt()).isEqualTo(4);
      assertThat(jurisdiction.path("comparablesExcluded").asInt()).isEqualTo(12);
      assertThat(report.toString()).doesNotContain(id, token, unavailable.at("/case/id").asText(),
          thin.at("/case/id").asText(), "1HGCM82633A004352", "licensed.example", "27604");
      assertThat(ServiceOperationsAcceptanceTest.SqlCapture.statements).noneMatch(sql ->
          sql.contains("vehicle_json") || sql.contains("listing_url") || sql.contains("price_minor") || sql.contains("m.*"));

      // Fixed durations and cost create deterministic continuous p95 and combined per-case cost.
      mutate(app, "update provider_usage set cost_usd = 0.30, finished_at = created_at + interval '1' second");
      report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/observed/observedCostPerCaseUsd").decimalValue()).isEqualByComparingTo("0.50");
      assertThat(report.at("/observed/maximumObservedCostPerCaseUsd").decimalValue()).isEqualByComparingTo("0.90");
      assertThat(report.at("/observed/p95LatencyMillis").asDouble()).isEqualTo(1000);
      assertThat(report.at("/observed/costComparison").asText()).isEqualTo("OVER");
      assertThat(report.at("/observed/latencyComparison").asText()).isEqualTo("OVER");
      mutate(app, "update provider_usage set finished_at = created_at + interval '2' second where operation = 'LISTING_RETRIEVAL'");
      report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/observed/p95LatencyMillis").asDouble()).isEqualTo(1800);
      var listing = report.path("providers").get(0);
      assertThat(listing.path("operation").asText()).isEqualTo("LISTING_RETRIEVAL");
      assertThat(listing.path("calls").asInt()).isEqualTo(1);
      assertThat(listing.path("meanKnownCallCostUsd").decimalValue()).isEqualByComparingTo("0.30");
      assertThat(listing.path("latencySamples").asInt()).isEqualTo(1);
      assertThat(listing.path("meanLatencyMillis").asDouble()).isEqualTo(2000);
      assertThat(listing.path("p95LatencyMillis").asDouble()).isEqualTo(2000);
      mutate(app, "update provider_usage set cost_usd = 0.10");
      report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/observed/observedCostPerCaseUsd").decimalValue()).isLessThan(new java.math.BigDecimal("0.25"));
      assertThat(report.at("/observed/maximumObservedCostPerCaseUsd").decimalValue()).isEqualByComparingTo("0.30");
      assertThat(report.at("/observed/costComparison").asText()).isEqualTo("OVER");
      mutate(app, "update provider_usage set cost_usd = 0.30");
      mutate(app, "update provider_usage set cost_usd = null where operation = 'LISTING_RETRIEVAL'");
      report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/observed/callsWithUnknownCost").asInt()).isEqualTo(1);
      assertThat(report.at("/observed/knownCostUsd").decimalValue()).isEqualByComparingTo("1.20");
      assertThat(report.at("/observed/observedCostPerCaseUsd").isNull()).isTrue();
      assertThat(report.at("/observed/maximumObservedCostPerCaseUsd").isNull()).isTrue();
      assertThat(report.at("/observed/costComparison").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(report.path("providers").findValuesAsText("callsWithUnknownCost")).contains("1");
      assertThat(report.at("/providers/0/knownCostUsd").isNull()).isTrue();
      assertThat(report.at("/providers/0/meanKnownCallCostUsd").isNull()).isTrue();
      mutate(app, "update provider_usage set finished_at = null where operation = 'LISTING_RETRIEVAL'");
      assertThat(operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT).at("/observed/latencyComparison").asText())
          .isEqualTo("NOT_MEASURABLE");
      // Another policy version must not multiply cases; another jurisdiction must retain zero samples.
      mutate(app, "insert into jurisdiction_policies select 'US-NC.cash.v2', jurisdiction, currency, tax_rate, taxable_line_types, trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance, policy_json, effective_at from jurisdiction_policies where policy_version = 'US-NC.cash.v1'");
      mutate(app, "insert into jurisdiction_policies select 'US-VA.test.v1', 'US-VA', currency, tax_rate, taxable_line_types, trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance, policy_json, effective_at from jurisdiction_policies where policy_version = 'US-NC.cash.v1'");
      var noAssessment = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      events.record(noAssessment.at("/case/id").asText(), PilotEvents.Type.MARKET_COVERAGE, PilotEvents.Category.UNAVAILABLE, null);
      post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase()); // No attempt, excluded.
      report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.path("jurisdictions").findValuesAsText("jurisdiction")).containsExactly("US-FL", "US-NC", "US-VA");
      assertThat(jurisdiction(report, "US-NC").at("/casesAttempted").asInt()).isEqualTo(4);
      assertThat(jurisdiction(report, "US-NC").at("/casesWithAssessment").asInt()).isEqualTo(3);
      assertThat(jurisdiction(report, "US-NC").at("/casesWithoutAssessment").asInt()).isEqualTo(1);
      assertThat(jurisdiction(report, "US-NC").at("/casesWithCoverageEvent").asInt()).isEqualTo(3);
      assertThat(jurisdiction(report, "US-VA").path("jurisdiction").asText()).isEqualTo("US-VA");
      assertThat(jurisdiction(report, "US-VA").at("/casesAttempted").asInt()).isZero();
    }
  }

  @Test void emptyWindowAndUnsetBudgetNeverProduceAPassingComparison() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("empty-evidence", false, ServiceOperationsAcceptanceTest.SETTINGS)) {
      var report = operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(report.at("/budget/status").asText()).isEqualTo("UNSET");
      assertThat(report.at("/budget/perCaseCostUsd").isNull()).isTrue();
      assertThat(report.at("/observed/calls").asInt()).isZero();
      assertThat(report.at("/observed/knownCostUsd").isNull()).isTrue();
      assertThat(report.at("/observed/p95LatencyMillis").isNull()).isTrue();
      assertThat(report.at("/observed/costComparison").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(report.at("/observed/latencyComparison").asText()).isEqualTo("NOT_MEASURABLE");
      assertThat(jurisdiction(report, "US-NC").at("/casesAttempted").asInt()).isZero();
      assertThat(jurisdiction(report, "US-NC").at("/comparablesEvaluated").asInt()).isZero();
      create(app, "Accord", 2021);
      assertThat(operator(app, PATH, ServiceOperationsAcceptanceTest.SUPPORT).at("/observed/costComparison").asText())
          .isEqualTo("NOT_MEASURABLE");
      // Half-open windows exclude a call or assessment at the upper boundary.
      mutate(app, "update provider_usage set created_at = timestamp with time zone '2020-01-02 00:00:00+00'");
      mutate(app, "update market_assessments set created_at = timestamp with time zone '2020-01-02 00:00:00+00'");
      var excluded = operator(app, PATH.replace("2100-01-01", "2020-01-02"), ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(excluded.at("/observed/calls").asInt()).isZero();
      assertThat(excluded.at("/jurisdictions/0/casesAttempted").asInt()).isZero();
      var included = operator(app, PATH.replace("2020-01-01", "2020-01-02"), ServiceOperationsAcceptanceTest.SUPPORT);
      assertThat(included.at("/observed/calls").asInt()).isEqualTo(1);
      assertThat(jurisdiction(included, "US-NC").at("/casesAttempted").asInt()).isEqualTo(1);
    }
  }

  private JsonNode create(RunningApplication app, String model, int year) throws Exception {
    var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
    String path = "/api/v1/cases/" + created.at("/case/id").asText();
    String token = created.path("accessToken").asText();
    post(app.client(), path + "/vehicle-evidence", Map.of("confirmed", true, "manualFacts",
        Map.of("year", year, "make", "Honda", "model", model, "trim", "EX-L", "mileage", 38000)), "vehicle", token);
    post(app.client(), path + "/market-assessments", Map.of(), "market", token);
    return created;
  }

  /** Report rows are per state; county-scoped packs such as US-FL:<county> roll up into their state. */
  private static JsonNode jurisdiction(JsonNode report, String code) {
    for (JsonNode row : report.path("jurisdictions")) if (code.equals(row.path("jurisdiction").asText())) return row;
    throw new AssertionError("No jurisdiction row " + code + " in " + report.path("jurisdictions"));
  }

  private JsonNode operator(RunningApplication app, String path, String token) throws Exception {
    return new ObjectMapper().readTree(app.client().get().uri(path).header("Authorization", "Bearer " + token)
        .retrieve().body(String.class));
  }

  private void mutate(RunningApplication app, String sql) {
    new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
        app.context().getBean(EntityManager.class).createNativeQuery(sql).executeUpdate());
  }
}
