package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;
import com.dealanalyzer.insurance.quote.*;
import com.dealanalyzer.insurance.quote.InsuranceApplication.*;
import com.dealanalyzer.operations.ProviderMeter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises real migrations and ProviderUsageStore transactions; no insurance HTTP endpoint needed. */
class InsuranceQuoteMeteringAcceptanceTest {
  @TempDir Path directory;
  @Test void eachCaseScopedRequestPersistsPartnerLicenceResultLatencyAndCostWithoutApplicationDetails() throws Exception {
    try (var app = new HttpAcceptanceHarness(directory).start("insurance-quote-meter", false)) {
      var created = post(app.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      String id = created.at("/case/id").asText();
      var application = new InsuranceApplication(List.of(new Driver(41, 22, 0, 0)), new Garaging("33602", "FL"),
          new InsuredVehicle("1HGCM82633A004352", 2023, "Honda", "Accord", "EX", 12000),
          CoverageSet.of(new Coverage(Coverage.Type.BODILY_INJURY_LIABILITY, 1000000L, 2000000L, null),
              new Coverage(Coverage.Type.COLLISION, null, null, 50000L)),
          new CurrentPolicy("Current Insurer", null, 91000, Term.SIX_MONTHS));
      var consent = new InsuranceQuoteConsent("synthetic-partner-consent.v1", Instant.now());
      var scope = InsuranceQuoteScope.forCase(id);
      var result = app.context().getBean(InsuranceQuoteService.class).request(scope, application, consent);
      assertThat(result.insurers()).hasSize(5);
      var jdbc = app.context().getBean(JdbcTemplate.class);
      var row = jdbc.queryForMap("select * from provider_usage where case_id = ? and operation = 'INSURANCE_QUOTE'", id);
      assertThat(row.get("provider")).isEqualTo(result.provider().name());
      assertThat(row.get("provider_version")).isEqualTo(result.provider().version());
      assertThat(row.get("license")).isEqualTo(result.provider().licenceMetadata());
      assertThat(row.get("schema_version")).isEqualTo("insurance-quote.v1");
      assertThat(row.get("result_count")).isEqualTo(4);
      assertThat(row.get("inventory_availability")).isEqualTo("AVAILABLE");
      assertThat(row.get("status")).isEqualTo("SUCCESS");
      assertThat(((Number) row.get("latency_millis")).longValue()).isNotNegative();
      assertThat((BigDecimal) row.get("cost_usd")).isZero();
      assertThat(row.get("retrieved_at")).isNotNull(); assertThat(row.get("finished_at")).isNotNull();
      assertThat(row.get("query_json")).isNull();
      assertThat(row.values()).noneMatch(value -> value != null
          && List.of("33602", "1HGCM82633A004352", "Accord", "Current Insurer").stream().anyMatch(value.toString()::contains));

      var provider = new InsuranceQuoteAdapter.Provider("LICENSED_TEST", "v1", "TEST_EXECUTED_TERMS", false);
      var limited = InsuranceQuoteContract.unavailable(provider, InsuranceQuoteAdapter.Reason.RATE_LIMITED,
          Instant.now(), new BigDecimal("0.125"));
      var metered = app.context().getBean(ProviderMeter.class).callInsuranceQuote(scope, provider, () -> limited);
      assertThat(metered.insurers()).isEmpty();
      var outage = jdbc.queryForMap("select * from provider_usage where case_id = ? and provider = 'LICENSED_TEST'", id);
      assertThat(outage.get("status")).isEqualTo("PROVIDER_UNAVAILABLE");
      assertThat(outage.get("result_count")).isEqualTo(0);
      assertThat(outage.get("inventory_availability")).isEqualTo("RATE_LIMITED");
      assertThat((BigDecimal) outage.get("cost_usd")).isEqualByComparingTo("0.125");

      var calls = new java.util.concurrent.atomic.AtomicBoolean();
      assertThatThrownBy(() -> app.context().getBean(ProviderMeter.class).callInsuranceQuote(
          InsuranceQuoteScope.forCase("missing-case"), provider, () -> { calls.set(true); return limited; }))
          .isInstanceOf(com.dealanalyzer.buyingcase.ResourceNotFoundException.class);
      assertThat(calls).isFalse();
    }
  }
}
